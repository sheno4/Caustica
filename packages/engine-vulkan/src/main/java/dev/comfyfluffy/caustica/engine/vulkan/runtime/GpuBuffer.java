package dev.comfyfluffy.caustica.engine.vulkan.runtime;

/** Renderer-owned Vulkan buffer allocation with a stable device address. */
public interface GpuBuffer {
    /** Raw {@code VkBuffer}; LWJGL represents the non-dispatchable handle as {@code long}. */
    long handle();

    /** {@code VkDeviceAddress} for the first byte of the allocation. */
    long deviceAddress();

    /** Persistently mapped host address, or zero for device-only memory. */
    long mapped();

    /** Buffer capacity in bytes. */
    long size();

    /** Destroy the buffer after every GPU use has drained. */
    void destroy();

    void flush();

    void flush(long offset, long length);

    void invalidate();

    void invalidate(long offset, long length);
}
