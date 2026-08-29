package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorWriter;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class VulkanDescriptorHeapTest {
    @Test
    void alignsNonCoherentFlushesAndClampsTheAllocationTail() {
        assertArrayEquals(new long[]{256, 256},
                VulkanDescriptorHeap.alignedFlushRange(300, 32, 1024, 256));
        assertArrayEquals(new long[]{768, 232},
                VulkanDescriptorHeap.alignedFlushRange(990, 10, 1000, 256));
    }

    @Test
    void rejectsFlushRangesOutsideTheHeapAllocation() {
        assertThrows(IllegalArgumentException.class,
                () -> VulkanDescriptorHeap.alignedFlushRange(1000, 1, 1000, 256));
        assertThrows(IllegalArgumentException.class,
                () -> VulkanDescriptorHeap.alignedFlushRange(0, 0, 1000, 256));
    }

    @Test
    void imageBatchPreservesKindsViewsAndLayouts() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo.Buffer views = VkImageViewCreateInfo.calloc(2, stack);
            views.get(0).sType$Default().image(41L);
            views.get(1).sType$Default().image(42L);
            VkImageDescriptorInfoEXT.Buffer images = VkImageDescriptorInfoEXT.calloc(2, stack);
            images.get(0).sType$Default().pView(views.get(0)).layout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            images.get(1).sType$Default().pView(views.get(1)).layout(VK13.VK_IMAGE_LAYOUT_READ_ONLY_OPTIMAL);

            var resources = VulkanDescriptorHeap.imageResources(stack, List.of(
                    new GpuDescriptorWriter.ImageWrite(GpuImageDescriptorKind.STORAGE, images.get(0)),
                    new GpuDescriptorWriter.ImageWrite(GpuImageDescriptorKind.SAMPLED, images.get(1))));

            assertEquals(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, resources.get(0).type());
            assertEquals(41L, resources.get(0).data().pImage().pView().image());
            assertEquals(VK10.VK_IMAGE_LAYOUT_GENERAL, resources.get(0).data().pImage().layout());
            assertEquals(VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, resources.get(1).type());
            assertEquals(42L, resources.get(1).data().pImage().pView().image());
            assertEquals(VK13.VK_IMAGE_LAYOUT_READ_ONLY_OPTIMAL, resources.get(1).data().pImage().layout());
        }
    }
}
