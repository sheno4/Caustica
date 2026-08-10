package dev.comfyfluffy.caustica.rt;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import static org.lwjgl.vulkan.KHRSynchronization2.VK_ACCESS_2_MEMORY_READ_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_ACCESS_2_MEMORY_WRITE_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR;

/** Common all-command memory dependency used between renderer passes recorded into one command buffer. */
public final class VulkanBarriers {
    private VulkanBarriers() {
    }

    public static void memoryBarrier(VkCommandBuffer commandBuffer, MemoryStack stack) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR)
                .srcAccessMask(VK_ACCESS_2_MEMORY_READ_BIT_KHR | VK_ACCESS_2_MEMORY_WRITE_BIT_KHR)
                .dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR)
                .dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT_KHR | VK_ACCESS_2_MEMORY_WRITE_BIT_KHR);
        VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
    }
}
