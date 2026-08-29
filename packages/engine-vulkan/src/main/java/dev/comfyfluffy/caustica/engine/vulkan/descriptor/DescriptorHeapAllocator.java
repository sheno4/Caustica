package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Thread-safe first-fit suballocator for the application-visible slots of one descriptor heap. */
public final class DescriptorHeapAllocator<I extends GpuDescriptorIndex> {
    private final DescriptorHeapLayout layout;
    private final IntFunction<I> indexFactory;
    private final TreeMap<Integer, Integer> freeRanges = new TreeMap<>();
    private final Map<Long, DescriptorHeapAllocation<I>> live = new HashMap<>();
    private long nextIdentity = 1;

    DescriptorHeapAllocator(DescriptorHeapLayout layout, IntFunction<I> indexFactory) {
        this.layout = layout;
        this.indexFactory = indexFactory;
        freeRanges.put(0, layout.applicationCapacity());
    }

    public DescriptorHeapLayout layout() {
        return layout;
    }

    public synchronized DescriptorHeapAllocation<I> allocate(int descriptorCount) {
        if (descriptorCount <= 0 || descriptorCount > layout.maximumAllocation()) {
            throw new IllegalArgumentException("descriptor count must be between 1 and " + layout.maximumAllocation());
        }
        Map.Entry<Integer, Integer> selected = freeRanges.entrySet().stream()
                .filter(entry -> entry.getValue() >= descriptorCount)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("descriptor heap is fragmented or exhausted"));
        int relativeFirst = selected.getKey();
        int remaining = selected.getValue() - descriptorCount;
        freeRanges.remove(relativeFirst);
        if (remaining != 0) freeRanges.put(relativeFirst + descriptorCount, remaining);

        if (nextIdentity <= 0) throw new IllegalStateException("descriptor allocation identity space exhausted");
        long identity = nextIdentity++;
        int absoluteFirst = Math.addExact(layout.firstApplicationIndex(), relativeFirst);
        DescriptorHeapAllocation<I> allocation = new DescriptorHeapAllocation<>(
                this, identity, indexFactory.apply(absoluteFirst), descriptorCount);
        live.put(identity, allocation);
        return allocation;
    }

    synchronized void retire(DescriptorHeapAllocation<I> allocation) {
        if (allocation.retired()) {
            throw new IllegalStateException("descriptor allocation " + allocation.identity() + " is already retired");
        }
        DescriptorHeapAllocation<I> owned = live.get(allocation.identity());
        if (owned != allocation) throw new IllegalArgumentException("allocation is not live in this heap");
        allocation.markRetired();
        live.remove(allocation.identity());
        int relativeFirst = allocation.firstIndex().value() - layout.firstApplicationIndex();
        insertAndCoalesce(relativeFirst, allocation.descriptorCount());
    }

    public synchronized int availableDescriptors() {
        return freeRanges.values().stream().mapToInt(Integer::intValue).sum();
    }

    public synchronized int liveAllocationCount() {
        return live.size();
    }

    DescriptorHeapWriteSpan writeSpan(DescriptorHeapAllocation<I> allocation, int relativeIndex) {
        synchronized (this) {
            return validatedWriteSpan(allocation, relativeIndex);
        }
    }

    /** Keeps a validated span live and unavailable for reuse through the complete native write. */
    synchronized void withWriteSpan(DescriptorHeapAllocation<I> allocation, int relativeIndex,
                                    Consumer<DescriptorHeapWriteSpan> write) {
        withWriteSpan(allocation, relativeIndex, 1, write);
    }

    /** Keeps one contiguous validated span live through the complete native write. */
    synchronized void withWriteSpan(DescriptorHeapAllocation<I> allocation, int relativeIndex,
                                    int descriptorCount, Consumer<DescriptorHeapWriteSpan> write) {
        write.accept(validatedWriteSpan(allocation, relativeIndex, descriptorCount));
    }

    private DescriptorHeapWriteSpan validatedWriteSpan(DescriptorHeapAllocation<I> allocation, int relativeIndex) {
        return validatedWriteSpan(allocation, relativeIndex, 1);
    }

    private DescriptorHeapWriteSpan validatedWriteSpan(DescriptorHeapAllocation<I> allocation, int relativeIndex,
                                                        int descriptorCount) {
        if (live.get(allocation.identity()) != allocation || allocation.retired()) {
            throw new IllegalStateException("descriptor allocation is not live");
        }
        if (descriptorCount <= 0) throw new IllegalArgumentException("descriptor count must be positive");
        if (relativeIndex < 0 || relativeIndex > allocation.descriptorCount() - descriptorCount) {
            throw new IndexOutOfBoundsException(relativeIndex);
        }
        int absoluteIndex = Math.addExact(allocation.firstIndex().value(), relativeIndex);
        return new DescriptorHeapWriteSpan(layout.kind(), allocation.identity(), absoluteIndex,
                layout.byteOffset(absoluteIndex), Math.multiplyExact(layout.descriptorStrideBytes(), descriptorCount));
    }

    private void insertAndCoalesce(int first, int count) {
        Map.Entry<Integer, Integer> lower = freeRanges.floorEntry(first);
        if (lower != null && lower.getKey() + lower.getValue() == first) {
            first = lower.getKey();
            count += lower.getValue();
            freeRanges.remove(lower.getKey());
        }
        Map.Entry<Integer, Integer> higher = freeRanges.ceilingEntry(first);
        if (higher != null && first + count == higher.getKey()) {
            count += higher.getValue();
            freeRanges.remove(higher.getKey());
        }
        freeRanges.put(first, count);
    }
}
