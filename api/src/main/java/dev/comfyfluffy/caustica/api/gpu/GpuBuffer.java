package dev.comfyfluffy.caustica.api.gpu;

/**
 * An extension-owned Vulkan buffer allocated through the renderer's device service.
 *
 * <p>The extension that creates a buffer through {@link GpuDevice#createBuffer} owns it. Free it through
 * {@link GpuDevice#retireAfterUse}, not by calling {@link #destroy()} directly — the only place a direct
 * destroy is safe is a lifecycle's final callback, where the device is already idle.
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
