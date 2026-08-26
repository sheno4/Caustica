package dev.comfyfluffy.caustica.api.gpu;

/**
 * An extension-owned suballocation of a renderer-bound descriptor heap.
 *
 * <p>The buffer remains renderer-owned. The extension owns only this range and releases it through
 * {@link GpuDevice#retireAfterUse} or {@link GpuFrameUse#retire}; direct destruction is safe only at a
 * lifecycle's final device-idle callback. Never overwrite descriptor bytes that submitted GPU work may
 * still read: allocate a replacement range, publish its offset, and retire this one.
 */
public interface GpuDescriptorRange {
    GpuDescriptorHeap.Kind kind();

    /** Renderer-owned Vulkan buffer containing this range. */
    long buffer();

    /** Byte offset from the beginning of the bound heap. */
    long offset();

    /** Device address of the first byte in this range. */
    long deviceAddress();

    /** Host pointer to the first byte, or {@code 0} when descriptor writes must be copied on the GPU. */
    long mapped();

    long size();

    /** Return this range to the heap allocator after every GPU use has retired. */
    void destroy();
}
