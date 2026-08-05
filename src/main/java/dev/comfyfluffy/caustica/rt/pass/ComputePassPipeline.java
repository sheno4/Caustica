package dev.comfyfluffy.caustica.rt.pass;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.api.pass.ComputeBinding;
import dev.comfyfluffy.caustica.api.pass.ComputeImageKind;
import dev.comfyfluffy.caustica.api.pass.ComputeProgram;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
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

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/** Vulkan backend for one declared compute program. Descriptor allocation and barriers stay engine-owned. */
final class ComputePassPipeline {
    private final RtContext ctx;
    private final ComputeProgram program;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long[] descriptorSets;
    private final long[][] boundViews;
    private final long pipelineLayout;
    private final long pipeline;
    private final long sampler;
    private int dispatchIndex;

    private ComputePassPipeline(RtContext ctx, ComputeProgram program, long descriptorSetLayout,
                                long descriptorPool, long[] descriptorSets, long pipelineLayout,
                                long pipeline, long sampler) {
        this.ctx = ctx;
        this.program = program;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSets = descriptorSets;
        this.boundViews = new long[descriptorSets.length][program.bindings().size()];
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.sampler = sampler;
    }

    static ComputePassPipeline create(RtContext ctx, ComputeProgram program, byte[] spirv, long sampler) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            List<ComputeBinding> declared = program.bindings();
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(declared.size(), stack);
            int storageCount = 0;
            int sampledCount = 0;
            for (int index = 0; index < declared.size(); index++) {
                ComputeImageKind kind = declared.get(index).kind();
                int descriptorType = kind == ComputeImageKind.STORAGE
                        ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                        : VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                bindings.get(index).binding(index).descriptorType(descriptorType)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
                if (kind == ComputeImageKind.STORAGE) {
                    storageCount++;
                } else {
                    sampledCount++;
                }
            }

            LongBuffer handle = stack.mallocLong(1);
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings);
            check(VK10.vkCreateDescriptorSetLayout(vk, layoutInfo, null, handle),
                    "vkCreateDescriptorSetLayout(" + program.id() + ")");
            long descriptorSetLayout = handle.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT,
                    descriptorSetLayout, program.id() + " descriptor set layout");

            int poolTypeCount = (storageCount > 0 ? 1 : 0) + (sampledCount > 0 ? 1 : 0);
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(poolTypeCount, stack);
            int poolIndex = 0;
            if (storageCount > 0) {
                poolSizes.get(poolIndex++).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(storageCount * program.maxDispatches());
            }
            if (sampledCount > 0) {
                poolSizes.get(poolIndex).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(sampledCount * program.maxDispatches());
            }
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(program.maxDispatches()).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, poolInfo, null, handle),
                    "vkCreateDescriptorPool(" + program.id() + ")");
            long descriptorPool = handle.get(0);

            LongBuffer setLayouts = stack.mallocLong(program.maxDispatches());
            for (int index = 0; index < program.maxDispatches(); index++) {
                setLayouts.put(index, descriptorSetLayout);
            }
            VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(descriptorPool).pSetLayouts(setLayouts);
            LongBuffer setHandles = stack.mallocLong(program.maxDispatches());
            check(VK10.vkAllocateDescriptorSets(vk, allocateInfo, setHandles),
                    "vkAllocateDescriptorSets(" + program.id() + ")");
            long[] descriptorSets = new long[program.maxDispatches()];
            for (int index = 0; index < descriptorSets.length; index++) {
                descriptorSets[index] = setHandles.get(index);
            }

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(descriptorSetLayout));
            if (program.pushConstantBytes() > 0) {
                VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
                pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                        .offset(0).size(program.pushConstantBytes());
                pipelineLayoutInfo.pPushConstantRanges(pushRange);
            }
            check(VK10.vkCreatePipelineLayout(vk, pipelineLayoutInfo, null, handle),
                    "vkCreatePipelineLayout(" + program.id() + ")");
            long pipelineLayout = handle.get(0);

            long module = createModule(vk, stack, spirv, program);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8(program.entryPoint()));
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            LongBuffer pipelineHandle = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, pipelineInfo, null,
                    pipelineHandle), "vkCreateComputePipelines(" + program.id() + ")");
            VK10.vkDestroyShaderModule(vk, module, null);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE,
                    pipelineHandle.get(0), program.id().toString());

            return new ComputePassPipeline(ctx, program, descriptorSetLayout, descriptorPool,
                    descriptorSets, pipelineLayout, pipelineHandle.get(0), sampler);
        }
    }

    void beginFrame() {
        dispatchIndex = 0;
    }

    void dispatch(VkCommandBuffer commandBuffer, RtImage[] images, byte[] pushConstants,
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
            VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
        }
    }

    private void updateDescriptorSet(int setIndex, RtImage[] images) {
        boolean changed = false;
        for (int index = 0; index < images.length; index++) {
            if (boundViews[setIndex][index] != images[index].view) {
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
                ComputeImageKind kind = program.bindings().get(index).kind();
                infos.get(index).imageView(images[index].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                int descriptorType;
                if (kind == ComputeImageKind.SAMPLED_LINEAR) {
                    infos.get(index).sampler(sampler);
                    descriptorType = VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                } else {
                    descriptorType = VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                }
                writes.get(index).sType$Default().dstSet(descriptorSets[setIndex]).dstBinding(index)
                        .descriptorCount(1).descriptorType(descriptorType)
                        .pImageInfo(VkDescriptorImageInfo.create(infos.address(index), 1));
                boundViews[setIndex][index] = images[index].view;
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    void destroy() {
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
    }

    private static long createModule(VkDevice vk, MemoryStack stack, byte[] spirv,
                                     ComputeProgram program) {
        ByteBuffer code = MemoryUtil.memAlloc(spirv.length).put(spirv).flip();
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(code);
            LongBuffer handle = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, info, null, handle),
                    "vkCreateShaderModule(" + program.id() + ")");
            return handle.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
