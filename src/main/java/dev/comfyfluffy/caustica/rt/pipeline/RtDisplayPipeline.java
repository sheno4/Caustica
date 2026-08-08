package dev.comfyfluffy.caustica.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.gen.DisplayPushData;

import static dev.comfyfluffy.caustica.rt.GpuContext.check;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.*;

/** Maps the display-res scene-linear ACEScg RT image to sRGB SDR and, when enabled, PQ/BT.2020 HDR. */
public final class RtDisplayPipeline {
    private static final String SHADER_DIR = "/caustica/shaders/pipelines/display/";
    private static final int RING = 6;
    /** Push constants: output/look LUT state plus gamma and HDR peak nits. */
    private static final int PUSH_BYTES = DisplayPushData.BYTE_SIZE;

    private final GpuContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long[] descriptorSets;
    private final RtGpuExecutor.TrackedGraphicsUse[] descriptorSetUses;
    private final ImageBindings[] descriptorSetBindings;
    private int currentSet = -1;
    private final long pipelineLayout;
    private final long pipeline;
    private boolean destroyed;

    private record ImageBindings(long outputView, long rtView, long exposureView, long hdrView,
                                 long lutView, long lutSampler, long hdrLutView, long hdrLutSampler,
                                 long lookLutView, long lookLutSampler) {
    }

    private RtDisplayPipeline(GpuContext ctx, long dsl, long pool, long[] sets, long layout, long pipeline) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSets = sets;
        this.descriptorSetUses = new RtGpuExecutor.TrackedGraphicsUse[sets.length];
        this.descriptorSetBindings = new ImageBindings[sets.length];
        for (int i = 0; i < sets.length; i++) {
            descriptorSetUses[i] = new RtGpuExecutor.TrackedGraphicsUse();
        }
        this.pipelineLayout = layout;
        this.pipeline = pipeline;
    }

    public static RtDisplayPipeline create(GpuContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(DISPLAY_BINDING_COUNT, stack);
            binds.get(DISPLAY_OUTPUT).binding(DISPLAY_OUTPUT).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(DISPLAY_RT_IMAGE).binding(DISPLAY_RT_IMAGE).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(DISPLAY_EXPOSURE).binding(DISPLAY_EXPOSURE).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(DISPLAY_HDR_OUTPUT).binding(DISPLAY_HDR_OUTPUT).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            // Baked ACES 2.0 display-transform LUTs; see RtToneLut.
            binds.get(DISPLAY_SDR_TONE_LUT).binding(DISPLAY_SDR_TONE_LUT).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(DISPLAY_HDR_TONE_LUT).binding(DISPLAY_HDR_TONE_LUT).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(DISPLAY_LOOK_LUT).binding(DISPLAY_LOOK_LUT).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout(rt display)");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, "display descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4 * RING);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(3 * RING);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(RING).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(rt display)");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, "display descriptor pool");

            LongBuffer setLayouts = stack.mallocLong(RING);
            for (int i = 0; i < RING; i++) {
                setLayouts.put(dsl);
            }
            setLayouts.flip();
            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(setLayouts);
            LongBuffer pSets = stack.mallocLong(RING);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSets), "vkAllocateDescriptorSets(rt display)");
            long[] sets = new long[RING];
            pSets.get(sets);
            for (int i = 0; i < RING; i++) {
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, sets[i],
                        "display descriptor set " + i);
            }

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pushRange);
            check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout(rt display)");
            long layout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, "display pipeline layout");

            long module = loadModule(vk, stack, "main.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, module, "display shader module");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, pPipeline),
                    "vkCreateComputePipelines(rt display)");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pPipeline.get(0), "display compute pipeline");
            VK10.vkDestroyShaderModule(vk, module, null);

            return new RtDisplayPipeline(ctx, dsl, pool, sets, layout, pPipeline.get(0));
        }
    }

    public void setImages(long outputImageView, long rtImageView, long exposureImageView, long hdrImageView,
                           long lutView, long lutSampler, long hdrLutView, long hdrLutSampler,
                           long lookLutView, long lookLutSampler,
                           RtGpuExecutor.GraphicsUse graphicsUse,
                           RtGpuExecutor.GraphicsUseWaiter graphicsUseWaiter) {
        ImageBindings wanted = new ImageBindings(outputImageView, rtImageView, exposureImageView, hdrImageView,
                lutView, lutSampler, hdrLutView, hdrLutSampler, lookLutView, lookLutSampler);
        if (currentSet >= 0 && wanted.equals(descriptorSetBindings[currentSet])) {
            descriptorSetUses[currentSet].mark(graphicsUse);
            return;
        }
        for (int i = 0; i < descriptorSets.length; i++) {
            if (wanted.equals(descriptorSetBindings[i])) {
                currentSet = i;
                descriptorSetUses[i].mark(graphicsUse);
                return;
            }
        }
        int nextSet = (currentSet + 1) % descriptorSets.length;
        graphicsUseWaiter.await(descriptorSetUses[nextSet]);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer outputInfo = VkDescriptorImageInfo.calloc(1, stack);
            outputInfo.get(0).imageView(outputImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer rtInfo = VkDescriptorImageInfo.calloc(1, stack);
            rtInfo.get(0).imageView(rtImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer exposureInfo = VkDescriptorImageInfo.calloc(1, stack);
            exposureInfo.get(0).imageView(exposureImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer hdrInfo = VkDescriptorImageInfo.calloc(1, stack);
            hdrInfo.get(0).imageView(hdrImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer lutInfo = VkDescriptorImageInfo.calloc(1, stack);
            lutInfo.get(0).imageView(lutView).sampler(lutSampler).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer hdrLutInfo = VkDescriptorImageInfo.calloc(1, stack);
            hdrLutInfo.get(0).imageView(hdrLutView).sampler(hdrLutSampler).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer lookLutInfo = VkDescriptorImageInfo.calloc(1, stack);
            lookLutInfo.get(0).imageView(lookLutView).sampler(lookLutSampler).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(DISPLAY_BINDING_COUNT, stack);
            writes.get(DISPLAY_OUTPUT).sType$Default().dstSet(descriptorSets[nextSet]).dstBinding(DISPLAY_OUTPUT)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(outputInfo);
            writes.get(DISPLAY_RT_IMAGE).sType$Default().dstSet(descriptorSets[nextSet]).dstBinding(DISPLAY_RT_IMAGE)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(rtInfo);
            writes.get(DISPLAY_EXPOSURE).sType$Default().dstSet(descriptorSets[nextSet]).dstBinding(DISPLAY_EXPOSURE)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(exposureInfo);
            writes.get(DISPLAY_HDR_OUTPUT).sType$Default().dstSet(descriptorSets[nextSet]).dstBinding(DISPLAY_HDR_OUTPUT)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(hdrInfo);
            writes.get(DISPLAY_SDR_TONE_LUT).sType$Default().dstSet(descriptorSets[nextSet]).dstBinding(DISPLAY_SDR_TONE_LUT)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(lutInfo);
            writes.get(DISPLAY_HDR_TONE_LUT).sType$Default().dstSet(descriptorSets[nextSet]).dstBinding(DISPLAY_HDR_TONE_LUT)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(hdrLutInfo);
            writes.get(DISPLAY_LOOK_LUT).sType$Default().dstSet(descriptorSets[nextSet]).dstBinding(DISPLAY_LOOK_LUT)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(lookLutInfo);
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        descriptorSetBindings[nextSet] = wanted;
        currentSet = nextSet;
        descriptorSetUses[nextSet].mark(graphicsUse);
    }

    /** Forget cached bindings after their image views are destroyed while the device is idle. */
    public void invalidateImages() {
        java.util.Arrays.fill(descriptorSetBindings, null);
        currentSet = -1;
    }

    /**
     * Run the display mapping through the baked ACES 2.0 LUTs: SDR
     * (binding 0) always writes; the PQ-encoded HDR image (binding 3) also writes when
     * {@code hdrEnabled}. The HDR LUT is baked for a fixed mastering-nits peak (see
     * {@code CausticaConfig.Rt.Hdr.PEAK_NITS_STEPS}), selected host-side by which LUT resource is bound.
     */
    public void dispatch(VkCommandBuffer cmd, int width, int height, boolean hdrEnabled, int lutSize,
                         float gamma, float hdrPeakNits, boolean lookEnabled, int lookLutSize) {
        if (currentSet < 0) {
            throw new IllegalStateException("display images were not bound before dispatch");
        }
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "display compute")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    stack.longs(descriptorSets[currentSet]), null);
            ByteBuffer push = stack.malloc(DisplayPushData.BYTE_SIZE);
            new DisplayPushData(hdrEnabled ? 1 : 0, (float) lutSize, gamma, hdrPeakNits,
                    lookEnabled ? 1 : 0, (float) lookLutSize).write(push);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        destroyed = true;
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtDisplayPipeline.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + name, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
