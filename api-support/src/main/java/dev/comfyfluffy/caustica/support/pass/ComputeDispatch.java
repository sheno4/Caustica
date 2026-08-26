package dev.comfyfluffy.caustica.support.pass;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.shader.ShaderBinding;
import dev.comfyfluffy.caustica.api.gpu.GpuImage;
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
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;


/**
 * Descriptor set / pipeline layout / compute pipeline for one shader, round-robined over
 * {@code maxDispatches} pre-allocated descriptor sets so a pass that dispatches the same program many
 * times per frame (e.g. one per bloom pyramid level) doesn't need a fresh set per call. A set is only
 * rewritten when its bound image views actually change from last time, so a pass whose bindings are
 * stable frame-to-frame (the sky LUTs) pays the {@code vkUpdateDescriptorSets} cost once.
 *
 * <p>Public pass-authoring helper: a compute-only pass (engine-bundled or extension-owned) is free to
 * build against this instead of rolling its own descriptor/pipeline setup, though it never has to — a
 * pass doing graphics work, or wanting something this doesn't offer, is free to write its own directly
 * against the {@link GpuDevice} supplied to a registered pass.
 */
public final class ComputeDispatch {
    private final GpuDevice ctx;
    private final List<ShaderBinding> bindings;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long[] descriptorSets;
    private final long[][] boundViews;
    private final long pipelineLayout;
    private final long pipeline;
    private final long sampler;
    private int dispatchIndex;
    private boolean destroyed;

    private ComputeDispatch(GpuDevice ctx, List<ShaderBinding> bindings, long descriptorSetLayout,
                            long descriptorPool, long[] descriptorSets, long pipelineLayout, long pipeline,
                            long sampler) {
        this.ctx = ctx;
        this.bindings = bindings;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSets = descriptorSets;
        this.boundViews = new long[descriptorSets.length][bindings.size()];
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.sampler = sampler;
    }

    public static long createLinearClampSampler(GpuDevice ctx, String label) {
        return createClampSampler(ctx, label, VK10.VK_FILTER_LINEAR);
    }

    /**
     * Clamped point sampling for discrete texel art rather than a smooth function. Filtering a sprite
     * magnified many times over blurs authored texels together. Use {@link #createLinearClampSampler} for
     * anything continuous, such as a baked LUT.
     */
    public static long createNearestClampSampler(GpuDevice ctx, String label) {
        return createClampSampler(ctx, label, VK10.VK_FILTER_NEAREST);
    }

    private static long createClampSampler(GpuDevice ctx, String label, int filter) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(filter).minFilter(filter)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f).maxLod(0.0f);
            LongBuffer handle = stack.mallocLong(1);
            check(VK10.vkCreateSampler(ctx.vk(), info, null, handle), "vkCreateSampler(" + label + ")");
            ctx.nameObject(VK10.VK_OBJECT_TYPE_SAMPLER, handle.get(0), label);
            return handle.get(0);
        }
    }

    public static ComputeDispatch create(GpuDevice ctx, String label, byte[] spirv, String entryPoint,
                                  List<ShaderBinding> bindings, int pushConstantBytes, int maxDispatches,
                                  long sampler) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer layoutBindings =
                    VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);
            int storageCount = 0;
            int sampledCount = 0;
            for (int index = 0; index < bindings.size(); index++) {
                ShaderBinding kind = bindings.get(index);
                int descriptorType = kind == ShaderBinding.STORAGE
                        ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                        : VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                layoutBindings.get(index).binding(index).descriptorType(descriptorType)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
                if (kind == ShaderBinding.STORAGE) {
                    storageCount++;
                } else {
                    sampledCount++;
                }
            }

            LongBuffer handle = stack.mallocLong(1);
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(layoutBindings);
            check(VK10.vkCreateDescriptorSetLayout(vk, layoutInfo, null, handle),
                    "vkCreateDescriptorSetLayout(" + label + ")");
            long descriptorSetLayout = handle.get(0);
            ctx.nameObject(VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT,
                    descriptorSetLayout, label + " descriptor set layout");

            int poolTypeCount = (storageCount > 0 ? 1 : 0) + (sampledCount > 0 ? 1 : 0);
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(poolTypeCount, stack);
            int poolIndex = 0;
            if (storageCount > 0) {
                poolSizes.get(poolIndex++).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(storageCount * maxDispatches);
            }
            if (sampledCount > 0) {
                poolSizes.get(poolIndex).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(sampledCount * maxDispatches);
            }
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(maxDispatches).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, poolInfo, null, handle),
                    "vkCreateDescriptorPool(" + label + ")");
            long descriptorPool = handle.get(0);

            LongBuffer setLayouts = stack.mallocLong(maxDispatches);
            for (int index = 0; index < maxDispatches; index++) {
                setLayouts.put(index, descriptorSetLayout);
            }
            VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(descriptorPool).pSetLayouts(setLayouts);
            LongBuffer setHandles = stack.mallocLong(maxDispatches);
            check(VK10.vkAllocateDescriptorSets(vk, allocateInfo, setHandles),
                    "vkAllocateDescriptorSets(" + label + ")");
            long[] descriptorSets = new long[maxDispatches];
            for (int index = 0; index < descriptorSets.length; index++) {
                descriptorSets[index] = setHandles.get(index);
            }

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(descriptorSetLayout));
            if (pushConstantBytes > 0) {
                VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
                pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                        .offset(0).size(pushConstantBytes);
                pipelineLayoutInfo.pPushConstantRanges(pushRange);
            }
            check(VK10.vkCreatePipelineLayout(vk, pipelineLayoutInfo, null, handle),
                    "vkCreatePipelineLayout(" + label + ")");
            long pipelineLayout = handle.get(0);

            long module = createModule(vk, stack, spirv, label);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8(entryPoint));
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            LongBuffer pipelineHandle = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, pipelineInfo, null,
                    pipelineHandle), "vkCreateComputePipelines(" + label + ")");
            VK10.vkDestroyShaderModule(vk, module, null);
            ctx.nameObject(VK10.VK_OBJECT_TYPE_PIPELINE, pipelineHandle.get(0), label);

            return new ComputeDispatch(ctx, bindings, descriptorSetLayout, descriptorPool,
                    descriptorSets, pipelineLayout, pipelineHandle.get(0), sampler);
        }
    }

    /** Reset the round-robin cursor; call once at the start of each frame before any {@link #dispatch}. */
    public void beginFrame() {
        dispatchIndex = 0;
    }

    /**
     * Bind and dispatch. Does not insert a barrier before or after — a pass that chains multiple
     * dispatches over the same images must insert its own via {@code PostEffectFrame.memoryBarrier}.
     */
    public void dispatch(VkCommandBuffer commandBuffer, GpuImage[] images, byte[] pushConstants,
                 int groupCountX, int groupCountY, int groupCountZ) {
        int setIndex = dispatchIndex++;
        updateDescriptorSet(setIndex, images);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, stack.longs(descriptorSets[setIndex]), null);
            if (pushConstants.length > 0) {
                ByteBuffer push = stack.malloc(pushConstants.length).put(pushConstants).flip();
                VK10.vkCmdPushConstants(commandBuffer, pipelineLayout,
                        VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            }
            VK10.vkCmdDispatch(commandBuffer, groupCountX, groupCountY, groupCountZ);
        }
    }

    private void updateDescriptorSet(int setIndex, GpuImage[] images) {
        boolean changed = false;
        for (int index = 0; index < images.length; index++) {
            if (boundViews[setIndex][index] != images[index].view()) {
                changed = true;
                break;
            }
        }
        if (!changed) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(images.length, stack);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(images.length, stack);
            for (int index = 0; index < images.length; index++) {
                ShaderBinding kind = bindings.get(index);
                infos.get(index).imageView(images[index].view()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                int descriptorType;
                if (kind == ShaderBinding.SAMPLED) {
                    infos.get(index).sampler(sampler);
                    descriptorType = VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                } else {
                    descriptorType = VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                }
                writes.get(index).sType$Default().dstSet(descriptorSets[setIndex]).dstBinding(index)
                        .descriptorCount(1).descriptorType(descriptorType)
                        .pImageInfo(VkDescriptorImageInfo.create(infos.address(index), 1));
                boundViews[setIndex][index] = images[index].view();
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
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

    private static long createModule(VkDevice vk, MemoryStack stack, byte[] spirv, String label) {
        ByteBuffer code = MemoryUtil.memAlloc(spirv.length).put(spirv).flip();
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(code);
            LongBuffer handle = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, info, null, handle), "vkCreateShaderModule(" + label + ")");
            return handle.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed: VkResult " + result);
        }
    }
}
