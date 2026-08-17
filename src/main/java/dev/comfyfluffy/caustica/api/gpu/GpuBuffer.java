package dev.comfyfluffy.caustica.api.gpu;

/**
 * A renderer-allocated Vulkan buffer with a device address.
 *
 * <p>The pass that creates a buffer through {@link GpuDevice#createBuffer} owns it and must call
 * {@link #destroy()} once no recorded or submitted work can reference it.
 */
public interface GpuBuffer {
    long handle();
    long deviceAddress();
    /** Host pointer if created host-visible, else {@code 0}. */
    long mapped();
    /** Allocated capacity in bytes. */
    long size();
    void destroy();
    /** Flush all host writes; coherent memory treats this as a no-op. */
    void flush();
    /** Flush a written byte range. */
    void flush(long offset, long length);
    /** Invalidate all host reads after GPU writes; coherent memory treats this as a no-op. */
    void invalidate();
    /** Invalidate a GPU-written byte range. */
    void invalidate(long offset, long length);
}
