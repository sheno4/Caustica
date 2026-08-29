package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDescriptorHeap;

import org.junit.jupiter.api.Test;

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
}
