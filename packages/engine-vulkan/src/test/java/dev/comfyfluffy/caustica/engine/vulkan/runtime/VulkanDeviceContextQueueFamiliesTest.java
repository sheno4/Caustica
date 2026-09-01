package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

final class VulkanDeviceContextQueueFamiliesTest {
    @Test
    void asyncBufferFamiliesContainDistinctGraphicsAndComputeFamilies() {
        assertArrayEquals(new int[] { 2, 7 }, VulkanDeviceContext.distinctQueueFamilies(2, 7));
    }

    @Test
    void asyncBufferFamiliesDeduplicateOneSharedFamily() {
        assertArrayEquals(new int[] { 3 }, VulkanDeviceContext.distinctQueueFamilies(3, 3));
    }
}
