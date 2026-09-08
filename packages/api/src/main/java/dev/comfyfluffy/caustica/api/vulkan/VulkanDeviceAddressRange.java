package dev.comfyfluffy.caustica.api.vulkan;

import java.util.Objects;

/**
 * A byte range in Vulkan device-addressable memory, without ownership of that memory.
 *
 * <p>This is the Java value counterpart of a {@code VkDeviceAddressRangeKHR}. It deliberately is not an
 * LWJGL native struct: retained API values must not borrow a {@code MemoryStack} or arena lifetime.
 * The caller separately keeps the addressed allocation alive through every GPU use.
 *
 * @param address first byte in the range
 * @param byteSize positive range length in bytes
 */
public record VulkanDeviceAddressRange(VulkanDeviceAddress address, long byteSize) {
    public VulkanDeviceAddressRange {
        Objects.requireNonNull(address, "address");
        if (byteSize <= 0L) throw new IllegalArgumentException("byteSize must be positive");
    }

    /** Selects a non-empty subrange and checks both size arithmetic and address overflow. */
    public VulkanDeviceAddressRange slice(long byteOffset, long sliceByteSize) {
        if (byteOffset < 0L) throw new IllegalArgumentException("byteOffset must be non-negative");
        if (sliceByteSize <= 0L) throw new IllegalArgumentException("sliceByteSize must be positive");
        long end = Math.addExact(byteOffset, sliceByteSize);
        if (end > byteSize) throw new IllegalArgumentException("slice exceeds device address range");
        return new VulkanDeviceAddressRange(address.addBytes(byteOffset), sliceByteSize);
    }
}
