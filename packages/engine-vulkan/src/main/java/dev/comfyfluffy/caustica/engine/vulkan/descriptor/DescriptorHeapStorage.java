package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;

/** Native heap memory supplied by the Vulkan device integration. */
public interface DescriptorHeapStorage extends AutoCloseable {
    DescriptorHeapKind kind();

    VulkanDeviceAddressRange deviceRange();

    long mappedAddress();

    void flush(long byteOffset, long byteSize);

    @Override
    void close();
}
