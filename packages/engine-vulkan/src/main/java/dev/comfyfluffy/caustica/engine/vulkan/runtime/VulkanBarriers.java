package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import static org.lwjgl.vulkan.KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_BLIT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COPY_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT;

/** Synchronization2 dependencies shared by renderer passes recorded into one command buffer. */
public final class VulkanBarriers {
    static final long PASS_STAGES = VK_PIPELINE_STAGE_2_COPY_BIT
            | VK_PIPELINE_STAGE_2_BLIT_BIT
            | VK_PIPELINE_STAGE_2_CLEAR_BIT
            | VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT
            | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
            | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT
            | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
            | VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT
            | VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
            | VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;
    static final long PASS_WRITE_ACCESS = VK_ACCESS_2_TRANSFER_WRITE_BIT
            | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
            | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT
            | VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
    static final long PASS_READ_WRITE_ACCESS = PASS_WRITE_ACCESS
            | VK_ACCESS_2_TRANSFER_READ_BIT
            | VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT
            | VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
            | VK_ACCESS_2_SHADER_STORAGE_READ_BIT
            | VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT
            | VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR;

    private VulkanBarriers() {
    }

    public static void memoryBarrier(VkCommandBuffer commandBuffer, MemoryStack stack) {
        VK13.vkCmdPipelineBarrier2(commandBuffer, passDependency(stack));
    }

    public static void worldResourcesToPrimary(VkCommandBuffer commandBuffer, MemoryStack stack) {
        VK13.vkCmdPipelineBarrier2(commandBuffer, worldResourcesToPrimaryDependency(stack));
    }

    /** Makes an earlier BLAS build or update visible to an UPDATE that reads it as its source. */
    public static void accelerationStructureBuildToUpdate(VkCommandBuffer commandBuffer, MemoryStack stack) {
        VK13.vkCmdPipelineBarrier2(commandBuffer, accelerationStructureBuildToUpdateDependency(stack));
    }

    static VkDependencyInfo accelerationStructureBuildToUpdateDependency(MemoryStack stack) {
        return dependency(stack,
                VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR);
    }

    static VkDependencyInfo worldResourcesToPrimaryDependency(MemoryStack stack) {
        return dependency(stack,
                VK_PIPELINE_STAGE_2_CLEAR_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                        | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }

    public static void primaryToIndirect(VkCommandBuffer commandBuffer, MemoryStack stack) {
        memoryBarrier(commandBuffer, stack,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }

    private static void memoryBarrier(VkCommandBuffer commandBuffer, MemoryStack stack,
                                      long sourceStage, long sourceAccess,
                                      long destinationStage, long destinationAccess) {
        VK13.vkCmdPipelineBarrier2(commandBuffer,
                dependency(stack, sourceStage, sourceAccess, destinationStage, destinationAccess));
    }

    private static VkDependencyInfo dependency(MemoryStack stack, long sourceStage, long sourceAccess,
                                               long destinationStage, long destinationAccess) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(sourceStage).srcAccessMask(sourceAccess)
                .dstStageMask(destinationStage).dstAccessMask(destinationAccess);
        return VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier);
    }

    static VkDependencyInfo passDependency(MemoryStack stack) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(PASS_STAGES)
                .srcAccessMask(PASS_WRITE_ACCESS)
                .dstStageMask(PASS_STAGES)
                .dstAccessMask(PASS_READ_WRITE_ACCESS);
        return VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier);
    }

    static void transitionUndefinedImage(VkCommandBuffer commandBuffer, MemoryStack stack, long image,
                                         long destinationStages, long destinationAccess) {
        VK13.vkCmdPipelineBarrier2(commandBuffer,
                undefinedImageDependency(stack, image, destinationStages, destinationAccess));
    }

    static VkDependencyInfo undefinedImageDependency(MemoryStack stack, long image,
                                                     long destinationStages, long destinationAccess) {
        VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_NONE)
                .srcAccessMask(VK_ACCESS_2_NONE)
                .dstStageMask(destinationStages)
                .dstAccessMask(destinationAccess)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.get(0).subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .levelCount(1)
                .layerCount(1);
        return VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier);
    }
}
