package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;

/** One live descriptor range. Its identity is never reused, even when its slots are. */
public final class DescriptorHeapAllocation<I extends GpuDescriptorIndex> implements GpuDescriptorRange<I> {
    private final DescriptorHeapAllocator<I> owner;
    private final long identity;
    private final I firstIndex;
    private final int descriptorCount;
    private boolean retired;

    DescriptorHeapAllocation(
            DescriptorHeapAllocator<I> owner,
            long identity,
            I firstIndex,
            int descriptorCount
    ) {
        this.owner = owner;
        this.identity = identity;
        this.firstIndex = firstIndex;
        this.descriptorCount = descriptorCount;
    }

    public long identity() {
        return identity;
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

    public synchronized boolean retired() {
        return retired;
    }

    synchronized void markRetired() {
        if (retired) throw new IllegalStateException("descriptor allocation " + identity + " is already retired");
        retired = true;
    }

    /** Retire this allocation and make its slots eligible for a later allocation. */
    @Override
    public void destroy() {
        owner.retire(this);
    }
}
