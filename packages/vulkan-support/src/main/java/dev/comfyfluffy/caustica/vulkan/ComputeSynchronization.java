package dev.comfyfluffy.caustica.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import java.util.List;
import java.util.Objects;

/** Synchronization2 barriers for extension-owned compute images in the unified GENERAL layout. */
public final class ComputeSynchronization {
    private static final long COMPUTE = VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
    private static final long SHADER_ACCESS = VK13.VK_ACCESS_2_SHADER_READ_BIT
            | VK13.VK_ACCESS_2_SHADER_WRITE_BIT;

    private ComputeSynchronization() { }

    /** Transition newly allocated images from UNDEFINED to the shared GENERAL layout. */
    public static void initializeImages(VkCommandBuffer commandBuffer, List<VmaImage2D> images) {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(images, "images");
        if (images.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(images.size(), stack);
            for (int index = 0; index < images.size(); index++) {
                VmaImage2D image = Objects.requireNonNull(images.get(index), "image");
                barriers.get(index).sType$Default()
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_NONE)
                        .srcAccessMask(VK13.VK_ACCESS_2_NONE)
                        .dstStageMask(COMPUTE).dstAccessMask(SHADER_ACCESS)
                        .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(image.image());
                barriers.get(index).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            }
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default()
                    .pImageMemoryBarriers(barriers);
            VK13.vkCmdPipelineBarrier2(commandBuffer, dependency);
        }
    }

    /** Make storage writes from one compute dispatch visible to subsequent compute reads and writes. */
    public static void betweenDispatches(VkCommandBuffer commandBuffer) {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0).sType$Default().srcStageMask(COMPUTE)
                    .srcAccessMask(VK13.VK_ACCESS_2_SHADER_WRITE_BIT)
                    .dstStageMask(COMPUTE).dstAccessMask(SHADER_ACCESS);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default()
                    .pMemoryBarriers(barrier);
            VK13.vkCmdPipelineBarrier2(commandBuffer, dependency);
        }
    }
}
