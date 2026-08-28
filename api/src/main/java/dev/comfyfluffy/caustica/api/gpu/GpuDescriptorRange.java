package dev.comfyfluffy.caustica.api.gpu;

/**
 * An extension-owned sequence of shader-visible slots in a renderer-bound descriptor heap.
 *
 * <p>The buffer remains renderer-owned. The extension owns only this range and releases it through
 * {@link GpuDevice#retireAfterUse} or {@link GpuFrameUse#retire}. A session-created pass may release its
 * exclusively owned range directly from its final callback, after that pass's uses have drained; this is
 * not a device-idle guarantee. Never overwrite descriptor bytes submitted work may still read: allocate a
 * replacement range, publish its first index, and retire this one.
 */
public interface GpuDescriptorRange {
    GpuDescriptorHeap.Kind kind();

    /** Index of the first slot, used directly with the matching Slang descriptor heap. */
    int firstIndex();

    int descriptorCount();

    /**
     * Return this range to the heap allocator after every GPU use has retired. Destruction is exactly once;
     * the range and its indices are invalid afterwards.
     */
    void destroy();
}
