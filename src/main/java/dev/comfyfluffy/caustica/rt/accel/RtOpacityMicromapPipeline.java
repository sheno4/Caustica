package dev.comfyfluffy.caustica.rt.accel;

import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

import static dev.comfyfluffy.caustica.rt.GpuContext.check;

/** Resource-epoch compute pipeline that classifies packed CUTOUT triangles into 4-state OMM data. */
public final class RtOpacityMicromapPipeline {
    public static final int PAGE_CAPACITY = 64;
    private static final int PUSH_BYTES = 80;
    private static final String SHADER = "/caustica/shaders/pipelines/opacity_micromap/main.comp.spv";

    private final GpuContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private boolean destroyed;

    private RtOpacityMicromapPipeline(GpuContext ctx, long descriptorSetLayout, long descriptorPool,
                                      long descriptorSet, long pipelineLayout, long pipeline) {
        this.ctx = ctx;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
    }

    public static RtOpacityMicromapPipeline create(GpuContext ctx, long[] temporalAlphaViews,
                                                    long[] staticAlphaViews) {
        if (temporalAlphaViews.length == 0 || temporalAlphaViews.length > PAGE_CAPACITY) return null;
        if (staticAlphaViews.length != temporalAlphaViews.length) return null;
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(2, stack);
            binding.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                    .descriptorCount(PAGE_CAPACITY).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binding.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                    .descriptorCount(PAGE_CAPACITY).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutBindingFlagsCreateInfo flags = VkDescriptorSetLayoutBindingFlagsCreateInfo
                    .calloc(stack).sType$Default().pBindingFlags(stack.ints(
                            VK12.VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT,
                            VK12.VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT));
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pNext(flags.address()).pBindings(binding);
            LongBuffer out = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, layoutInfo, null, out),
                    "vkCreateDescriptorSetLayout(opacity micromap)");
            long descriptorLayout = out.get(0);

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(PAGE_CAPACITY * 2);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(poolSize);
            check(VK10.vkCreateDescriptorPool(vk, poolInfo, null, out),
                    "vkCreateDescriptorPool(opacity micromap)");
            long pool = out.get(0);
            VkDescriptorSetAllocateInfo allocate = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(descriptorLayout));
            check(VK10.vkAllocateDescriptorSets(vk, allocate, out), "vkAllocateDescriptorSets(opacity micromap)");
            long set = out.get(0);
            VkDescriptorImageInfo.Buffer images = VkDescriptorImageInfo.calloc(temporalAlphaViews.length, stack);
            for (int i = 0; i < temporalAlphaViews.length; i++) {
                images.get(i).imageView(temporalAlphaViews[i]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            }
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().dstSet(set).dstBinding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                    .descriptorCount(temporalAlphaViews.length).pImageInfo(images);
            VK10.vkUpdateDescriptorSets(vk, write, null);
            VkDescriptorImageInfo.Buffer staticAlpha = VkDescriptorImageInfo.calloc(staticAlphaViews.length, stack);
            for (int i = 0; i < staticAlphaViews.length; i++) {
                staticAlpha.get(i).imageView(staticAlphaViews[i]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            }
            VkWriteDescriptorSet.Buffer staticAlphaWrite = VkWriteDescriptorSet.calloc(1, stack);
            staticAlphaWrite.get(0).sType$Default().dstSet(set).dstBinding(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                    .descriptorCount(staticAlphaViews.length)
                    .pImageInfo(staticAlpha);
            VK10.vkUpdateDescriptorSets(vk, staticAlphaWrite, null);

            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
            push.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(push);
            check(VK10.vkCreatePipelineLayout(vk, pipelineLayoutInfo, null, out),
                    "vkCreatePipelineLayout(opacity micromap)");
            long pipelineLayout = out.get(0);
            long module = loadModule(vk, stack);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, pipelineInfo, null, out),
                    "vkCreateComputePipelines(opacity micromap)");
            long pipeline = out.get(0);
            VK10.vkDestroyShaderModule(vk, module, null);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "opacity micromap classifier");
            return new RtOpacityMicromapPipeline(ctx, descriptorLayout, pool, set, pipelineLayout, pipeline);
        }
    }

    public void record(VkCommandBuffer command, long indexAddress, long textureCoordinateAddress,
                       long primitiveAddress, long materialTableAddress, long surfaceTableAddress,
                       long dataAddress, long triangleAddress, int maskedTriangleBase, int triangleCount,
                       int geometryFlags, int subdivisionLevel, int dataStride) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, command, "opacity micromap classify")) {
            VK10.vkCmdBindPipeline(command, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(command, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
                    0, stack.longs(descriptorSet), null);
            ByteBuffer push = stack.malloc(PUSH_BYTES).order(ByteOrder.nativeOrder());
            push.putLong(0, indexAddress).putLong(8, textureCoordinateAddress)
                    .putLong(16, primitiveAddress).putLong(24, materialTableAddress)
                    .putLong(32, surfaceTableAddress).putLong(40, dataAddress)
                    .putLong(48, triangleAddress).putInt(56, maskedTriangleBase)
                    .putInt(60, triangleCount).putInt(64, geometryFlags)
                    .putInt(68, subdivisionLevel).putInt(72, dataStride);
            push.position(0).limit(PUSH_BYTES);
            VK10.vkCmdPushConstants(command, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(command, (triangleCount + 63) / 64, 1, 1);
        }
    }

    public void destroy() {
        if (destroyed) return;
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        destroyed = true;
    }

    private static long loadModule(VkDevice vk, MemoryStack stack) {
        byte[] bytes;
        try (InputStream input = RtOpacityMicromapPipeline.class.getResourceAsStream(SHADER)) {
            if (input == null) throw new IllegalStateException("missing SPIR-V resource: " + SHADER);
            bytes = input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer module = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, info, null, module), "vkCreateShaderModule(opacity micromap)");
            return module.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
