package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;

/** NGX requires an explicit conventional descriptor bind even in a fresh command buffer. */
final class ConventionalDescriptorBindings implements AutoCloseable {
    private final VulkanDeviceContext context;
    private long setLayout;
    private long pipelineLayout;
    private long pool;
    private final long set;

    ConventionalDescriptorBindings(VulkanDeviceContext context) {
        this.context = context;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            context.checkDeviceResult(VK10.vkCreateDescriptorSetLayout(context.vk(),
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default(), null, handle),
                    "vkCreateDescriptorSetLayout(conventional commands)");
            setLayout = handle.get(0);
            context.checkDeviceResult(VK10.vkCreatePipelineLayout(context.vk(),
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)),
                    null, handle), "vkCreatePipelineLayout(conventional commands)");
            pipelineLayout = handle.get(0);
            context.checkDeviceResult(VK10.vkCreateDescriptorPool(context.vk(),
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1), null, handle),
                    "vkCreateDescriptorPool(conventional commands)");
            pool = handle.get(0);
            context.checkDeviceResult(VK10.vkAllocateDescriptorSets(context.vk(),
                    VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool)
                            .pSetLayouts(stack.longs(setLayout)), handle),
                    "vkAllocateDescriptorSets(conventional commands)");
            set = handle.get(0);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    void bind(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindDescriptorSets(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, stack.longs(set), null);
        }
    }

    @Override public void close() {
        VK10.vkDestroyDescriptorPool(context.vk(), pool, null);
        VK10.vkDestroyPipelineLayout(context.vk(), pipelineLayout, null);
        VK10.vkDestroyDescriptorSetLayout(context.vk(), setLayout, null);
    }
}
