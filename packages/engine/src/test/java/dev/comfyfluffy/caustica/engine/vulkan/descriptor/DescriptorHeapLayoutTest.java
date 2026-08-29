package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DescriptorHeapLayoutTest {
    @Test
    void reservesWholeSlotsBeforeApplicationDescriptors() {
        DescriptorHeapLayout layout = DescriptorHeapLayout.create(
                DescriptorHeapKind.RESOURCE, 32, 256, 33, 4096, 8, 4);

        assertEquals(64, layout.reservedRangeBytes());
        assertEquals(2, layout.firstApplicationIndex());
        assertEquals(320, layout.heapSizeBytes());
        assertEquals(64, layout.applicationOffsetBytes());
        assertEquals(64, layout.byteOffset(2));
        assertEquals(288, layout.byteOffset(9));
    }

    @Test
    void enforcesHeapSizeIndexSpaceAndAllocationLimits() {
        assertThrows(IllegalArgumentException.class, () -> DescriptorHeapLayout.create(
                DescriptorHeapKind.RESOURCE, 32, 256, 0, 255, 8, 4));
        assertThrows(IllegalArgumentException.class, () -> DescriptorHeapLayout.create(
                DescriptorHeapKind.RESOURCE, 32, 256, 0, 4096, 8, 9));
        assertThrows(IllegalArgumentException.class, () -> DescriptorHeapLayout.create(
                DescriptorHeapKind.RESOURCE, Long.MAX_VALUE, 256, 0, Long.MAX_VALUE, 2, 1));
    }

    @Test
    void validatesNativeStorageWithoutCreatingIt() {
        DescriptorHeapLayout layout = DescriptorHeapLayout.create(
                DescriptorHeapKind.SAMPLER, 8, 64, 8, 1024, 8, 4);

        layout.validateStorage(0x1000, layout.heapSizeBytes());
        assertThrows(IllegalArgumentException.class,
                () -> layout.validateStorage(0x1008, layout.heapSizeBytes()));
        assertThrows(IllegalArgumentException.class,
                () -> layout.validateStorage(0x1000, layout.heapSizeBytes() - 1));
    }
}
