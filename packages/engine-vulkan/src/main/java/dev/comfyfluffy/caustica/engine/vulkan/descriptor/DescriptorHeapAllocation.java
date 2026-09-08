package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;

/** One descriptor range; reusing its slots creates a separate allocation object. */
public final class DescriptorHeapAllocation<I extends GpuDescriptorIndex> implements GpuDescriptorRange<I> {
    private final DescriptorHeapAllocator<I> owner;
    private final I firstIndex;
    private final int descriptorCount;

    DescriptorHeapAllocation(
            DescriptorHeapAllocator<I> owner,
            I firstIndex,
            int descriptorCount
    ) {
        this.owner = owner;
        this.firstIndex = firstIndex;
        this.descriptorCount = descriptorCount;
    }

    public DescriptorHeapKind kind() {
        return owner.layout().kind();
    }

    @Override
    public I firstIndex() {
        return firstIndex;
    }

    @Override
    public int descriptorCount() {
        return descriptorCount;
    }

    /** Retire this allocation and make its slots eligible for a later allocation. */
    @Override
    public void destroy() {
        owner.retire(this);
    }
}
