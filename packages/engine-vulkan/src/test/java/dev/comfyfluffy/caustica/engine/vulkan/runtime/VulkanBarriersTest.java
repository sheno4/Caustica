package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanBarriers;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VulkanBarriersTest {
    @Test
    void passDependencyMakesWritesVisibleOnlyToRendererConsumerScopes() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDependencyInfo dependency = VulkanBarriers.passDependency(stack);
            VkMemoryBarrier2.Buffer barriers = dependency.pMemoryBarriers();

            assertNotNull(barriers);
            assertEquals(1, barriers.remaining());
            VkMemoryBarrier2 barrier = barriers.get(0);
            assertEquals(VulkanBarriers.PASS_STAGES, barrier.srcStageMask());
            assertEquals(VulkanBarriers.PASS_WRITE_ACCESS, barrier.srcAccessMask());
            assertEquals(VulkanBarriers.PASS_STAGES, barrier.dstStageMask());
            assertEquals(VulkanBarriers.PASS_READ_WRITE_ACCESS, barrier.dstAccessMask());
            assertNotEquals(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, barrier.srcStageMask());
            assertEquals(0L, barrier.srcStageMask() & VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT);
            assertTrue((barrier.srcStageMask() & VK13.VK_PIPELINE_STAGE_2_COPY_BIT) != 0L);
            assertTrue((barrier.srcStageMask() & VK13.VK_PIPELINE_STAGE_2_BLIT_BIT) != 0L);
            assertTrue((barrier.srcStageMask() & VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT) != 0L);
            assertEquals(0L, barrier.srcAccessMask() & VK13.VK_ACCESS_2_SHADER_WRITE_BIT);
            assertEquals(0L, barrier.dstAccessMask() & VK13.VK_ACCESS_2_SHADER_READ_BIT);
            assertTrue((barrier.srcAccessMask() & VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT) != 0L);
            assertTrue((barrier.dstAccessMask() & VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT) != 0L);
            assertTrue((barrier.dstAccessMask() & VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT) != 0L);
        }
    }

    @Test
    void undefinedImageTransitionUsesNoneAsItsEmptySourceScope() {
        long image = 0x1234L;
        long destinationStages = VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                | VK13.VK_PIPELINE_STAGE_2_COPY_BIT;
        long destinationAccess = VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT
                | VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDependencyInfo dependency = VulkanBarriers.undefinedImageDependency(
                    stack, image, destinationStages, destinationAccess);
            VkImageMemoryBarrier2.Buffer barriers = dependency.pImageMemoryBarriers();

            assertNotNull(barriers);
            assertEquals(1, barriers.remaining());
            VkImageMemoryBarrier2 barrier = barriers.get(0);
            assertEquals(VK13.VK_PIPELINE_STAGE_2_NONE, barrier.srcStageMask());
            assertEquals(VK13.VK_ACCESS_2_NONE, barrier.srcAccessMask());
            assertEquals(destinationStages, barrier.dstStageMask());
            assertEquals(destinationAccess, barrier.dstAccessMask());
            assertEquals(VK10.VK_IMAGE_LAYOUT_UNDEFINED, barrier.oldLayout());
            assertEquals(VK10.VK_IMAGE_LAYOUT_GENERAL, barrier.newLayout());
            assertEquals(VK10.VK_QUEUE_FAMILY_IGNORED, barrier.srcQueueFamilyIndex());
            assertEquals(VK10.VK_QUEUE_FAMILY_IGNORED, barrier.dstQueueFamilyIndex());
            assertEquals(image, barrier.image());
            assertEquals(VK10.VK_IMAGE_ASPECT_COLOR_BIT, barrier.subresourceRange().aspectMask());
            assertEquals(1, barrier.subresourceRange().levelCount());
            assertEquals(1, barrier.subresourceRange().layerCount());
        }
    }

    @Test
    void worldResourcesMakeComputeAndClearWritesVisibleToPrimaryRayTracing() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDependencyInfo dependency = VulkanBarriers.worldResourcesToPrimaryDependency(stack);
            VkMemoryBarrier2.Buffer barriers = dependency.pMemoryBarriers();

            assertNotNull(barriers);
            assertEquals(1, barriers.remaining());
            VkMemoryBarrier2 barrier = barriers.get(0);
            assertEquals(VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT | VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    barrier.srcStageMask());
            assertEquals(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                    barrier.srcAccessMask());
            assertEquals(org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                    barrier.dstStageMask());
            assertEquals(VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
                            | VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                            | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                    barrier.dstAccessMask());
        }
    }

    @Test
    void accelerationStructureUpdateReadsEarlierBuildWrites() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDependencyInfo dependency =
                    VulkanBarriers.accelerationStructureBuildToUpdateDependency(stack);
            VkMemoryBarrier2.Buffer barriers = dependency.pMemoryBarriers();

            assertNotNull(barriers);
            assertEquals(1, barriers.remaining());
            VkMemoryBarrier2 barrier = barriers.get(0);
            assertEquals(org.lwjgl.vulkan.KHRSynchronization2
                            .VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    barrier.srcStageMask());
            assertEquals(org.lwjgl.vulkan.KHRSynchronization2
                            .VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    barrier.srcAccessMask());
            assertEquals(org.lwjgl.vulkan.KHRSynchronization2
                            .VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    barrier.dstStageMask());
            assertEquals(org.lwjgl.vulkan.KHRSynchronization2
                            .VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR,
                    barrier.dstAccessMask());
        }
    }
}
