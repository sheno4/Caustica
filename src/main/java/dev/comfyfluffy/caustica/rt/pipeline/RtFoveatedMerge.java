package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.joml.Matrix4fc;
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
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.nio.ByteBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/** Combines both reconstructed layers in scene-linear color before exposure and bloom. */
public final class RtFoveatedMerge {
    private static final int PUSH_SIZE = 16 * Float.BYTES;
    private final VkDevice device;
    private long descriptorLayout;
    private long pool;
    private long set;
    private long layout;
    private long pipeline;

    private RtFoveatedMerge(VkDevice device) {
        this.device = device;
    }

    public static RtFoveatedMerge create(RtContext ctx, RtImage output, RtImage wide, RtImage inset, long sampler) {
        var merge = new RtFoveatedMerge(ctx.vk());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            merge.initialize(stack, new RtImage[]{output, wide, inset}, sampler);
            return merge;
        } catch (RuntimeException | Error failure) {
            merge.destroy();
            throw failure;
        }
    }

    private void initialize(MemoryStack stack, RtImage[] images, long sampler) {
        int storage = VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        int sampled = VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        int compute = VK10.VK_SHADER_STAGE_COMPUTE_BIT;
        var bindings = VkDescriptorSetLayoutBinding.calloc(images.length, stack);
        for (int i = 0; i < images.length; i++) {
            bindings.get(i).binding(i).descriptorType(i == 0 ? storage : sampled)
                    .descriptorCount(1).stageFlags(compute);
        }
        var handle = stack.mallocLong(1);
        var descriptorInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
        check(VK10.vkCreateDescriptorSetLayout(device, descriptorInfo, null, handle), "foveated descriptor layout");
        descriptorLayout = handle.get(0);

        var sizes = VkDescriptorPoolSize.calloc(2, stack);
        sizes.get(0).type(storage).descriptorCount(1);
        sizes.get(1).type(sampled).descriptorCount(2);
        var poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes);
        check(VK10.vkCreateDescriptorPool(device, poolInfo, null, handle), "foveated descriptor pool");
        pool = handle.get(0);
        var allocate = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(descriptorLayout));
        check(VK10.vkAllocateDescriptorSets(device, allocate, handle), "foveated descriptor set");
        set = handle.get(0);
        var writes = VkWriteDescriptorSet.calloc(images.length, stack);
        for (int i = 0; i < images.length; i++) {
            var info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).imageView(images[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .sampler(i == 0 ? VK10.VK_NULL_HANDLE : sampler);
            writes.get(i).sType$Default().dstSet(set).dstBinding(i)
                    .descriptorType(i == 0 ? storage : sampled).descriptorCount(1).pImageInfo(info);
        }
        VK10.vkUpdateDescriptorSets(device, writes, null);

        var pushRange = VkPushConstantRange.calloc(1, stack);
        pushRange.get(0).stageFlags(compute).offset(0).size(PUSH_SIZE);
        var layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(pushRange);
        check(VK10.vkCreatePipelineLayout(device, layoutInfo, null, handle), "foveated pipeline layout");
        layout = handle.get(0);
        long shader = loadShader(stack);
        try {
            var create = VkComputePipelineCreateInfo.calloc(1, stack);
            create.get(0).sType$Default().layout(layout)
                    .stage(stage -> stage.sType$Default().stage(compute).module(shader).pName(stack.UTF8("main")));
            check(VK10.vkCreateComputePipelines(device, VK10.VK_NULL_HANDLE, create, null, handle),
                    "foveated merge pipeline");
            pipeline = handle.get(0);
        } finally {
            VK10.vkDestroyShaderModule(device, shader, null);
        }
    }

    private long loadShader(MemoryStack stack) {
        String shader = "/caustica/shaders/pipelines/foveated_merge/main.comp.spv";
        try (var stream = RtFoveatedMerge.class.getResourceAsStream(shader)) {
            if (stream == null) {
                throw new IllegalStateException("Missing shader " + shader);
            }
            byte[] bytes = stream.readAllBytes();
            ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
            try {
                code.put(bytes).flip();
                var info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
                var handle = stack.mallocLong(1);
                check(VK10.vkCreateShaderModule(device, info, null, handle), "foveated merge shader");
                return handle.get(0);
            } finally {
                MemoryUtil.memFree(code);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + shader, failure);
        }
    }

    public void record(VkCommandBuffer cmd, MemoryStack stack, int width, int height, Matrix4fc eyeToInset) {
        int compute = VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
        VK10.vkCmdBindPipeline(cmd, compute, pipeline);
        VK10.vkCmdBindDescriptorSets(cmd, compute, layout, 0, stack.longs(set), null);
        var push = stack.malloc(PUSH_SIZE);
        eyeToInset.get(0, push);
        VK10.vkCmdPushConstants(cmd, layout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        VK10.vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);
    }

    public void destroy() {
        VK10.vkDestroyPipeline(device, pipeline, null);
        VK10.vkDestroyPipelineLayout(device, layout, null);
        VK10.vkDestroyDescriptorPool(device, pool, null);
        VK10.vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
    }
}
