package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

import dev.comfyfluffy.caustica.api.gpu.GpuDescriptorHeapProperties;
import dev.comfyfluffy.caustica.api.gpu.GpuDescriptorIndex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DescriptorHeapAllocatorTest {
    @Test
    void fragmentedRangesCoalesceAfterRetirement() {
        DescriptorHeapAllocator<GpuDescriptorIndex.Resource> allocator = resourceAllocator(8, 4);
        DescriptorHeapAllocation<GpuDescriptorIndex.Resource> first = allocator.allocate(3);
        DescriptorHeapAllocation<GpuDescriptorIndex.Resource> second = allocator.allocate(3);

        first.destroy();
        assertThrows(IllegalStateException.class, () -> allocator.allocate(4));

        second.destroy();
        DescriptorHeapAllocation<GpuDescriptorIndex.Resource> coalesced = allocator.allocate(4);
        assertEquals(2, coalesced.firstIndex().value());
        assertEquals(4, allocator.availableDescriptors());
    }

    @Test
    void slotsCannotBeReusedUntilTheAllocationIsExplicitlyRetired() {
        DescriptorHeapAllocator<GpuDescriptorIndex.Resource> allocator = resourceAllocator(4, 4);
        DescriptorHeapAllocation<GpuDescriptorIndex.Resource> first = allocator.allocate(4);

        assertThrows(IllegalStateException.class, () -> allocator.allocate(1));
        first.destroy();
        assertTrue(first.retired());

        DescriptorHeapAllocation<GpuDescriptorIndex.Resource> replacement = allocator.allocate(4);
        assertEquals(first.firstIndex(), replacement.firstIndex());
        assertNotEquals(first.identity(), replacement.identity());
        assertThrows(IllegalStateException.class, first::destroy);
        assertThrows(IllegalStateException.class, () -> allocator.writeSpan(first, 0));
    }

    @Test
    void validatesWritesAgainstTheStableLiveAllocationIdentity() {
        DescriptorHeapAllocator<GpuDescriptorIndex.Resource> allocator = resourceAllocator(8, 4);
        DescriptorHeapAllocation<GpuDescriptorIndex.Resource> allocation = allocator.allocate(3);

        DescriptorHeapWriteSpan span = allocator.writeSpan(allocation, 2);

        assertEquals(DescriptorHeapKind.RESOURCE, span.kind());
        assertEquals(allocation.identity(), span.allocationIdentity());
        assertEquals(4, span.absoluteIndex());
        assertEquals(128, span.byteOffset());
        assertEquals(32, span.byteSize());
        assertThrows(IndexOutOfBoundsException.class, () -> allocator.writeSpan(allocation, 3));
    }

    @Test
    void resourceAndSamplerHeapsKeepDistinctIndexTypesAndSlotSpaces() {
        GpuDescriptorHeapProperties properties = new GpuDescriptorHeapProperties(
                32, 8, 8, 4, 4, 2, 256, 64);
        DescriptorHeapAllocationCore core = new DescriptorHeapAllocationCore(
                properties, 33, 1, 4096, 1024);

        DescriptorHeapAllocation<GpuDescriptorIndex.Resource> resource = core.allocateResources(1);
        DescriptorHeapAllocation<GpuDescriptorIndex.Sampler> sampler = core.allocateSamplers(1);

        assertInstanceOf(GpuDescriptorIndex.Resource.class, resource.firstIndex());
        assertInstanceOf(GpuDescriptorIndex.Sampler.class, sampler.firstIndex());
        assertEquals(2, resource.firstIndex().value());
        assertEquals(1, sampler.firstIndex().value());
        assertEquals(DescriptorHeapKind.RESOURCE, resource.kind());
        assertEquals(DescriptorHeapKind.SAMPLER, sampler.kind());
    }

    @Test
    void rejectsInvalidCountsBeforeChangingAllocatorState() {
        DescriptorHeapAllocator<GpuDescriptorIndex.Resource> allocator = resourceAllocator(8, 4);

        assertThrows(IllegalArgumentException.class, () -> allocator.allocate(0));
        assertThrows(IllegalArgumentException.class, () -> allocator.allocate(5));
        assertEquals(8, allocator.availableDescriptors());
        assertEquals(0, allocator.liveAllocationCount());
    }

    private static DescriptorHeapAllocator<GpuDescriptorIndex.Resource> resourceAllocator(
            int capacity,
            int maximumAllocation
    ) {
        DescriptorHeapLayout layout = DescriptorHeapLayout.create(
                DescriptorHeapKind.RESOURCE, 32, 256, 64, 4096, capacity, maximumAllocation);
        return new DescriptorHeapAllocator<>(layout, GpuDescriptorIndex.Resource::new);
    }
}
