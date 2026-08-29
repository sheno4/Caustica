package dev.comfyfluffy.caustica.api.vulkan;

/**
 * A non-zero Vulkan {@code VkDeviceAddress} retained as an ordinary Java value.
 *
 * <p>{@code VkDeviceAddress} is a 64-bit integer typedef rather than a dispatchable Vulkan handle, so
 * LWJGL represents it as {@code long} and provides no {@code VkDeviceAddress} wrapper class. This type
 * prevents an address from being confused with an unrelated handle, byte count, or shader word while
 * remaining independent of native-memory lifetimes.
 *
 * @param value raw non-zero {@code VkDeviceAddress} bits
 */
public record VulkanDeviceAddress(long value) {
    public VulkanDeviceAddress {
        if (value == 0L) throw new IllegalArgumentException("device address must not be zero");
    }

    /** Returns an address advanced by a non-negative byte offset. */
    public VulkanDeviceAddress addBytes(long byteOffset) {
        if (byteOffset < 0L) throw new IllegalArgumentException("byteOffset must be non-negative");
        long result = value + byteOffset;
        if (Long.compareUnsigned(result, value) < 0) {
            throw new IllegalArgumentException("device address overflows uint64");
        }
        return new VulkanDeviceAddress(result);
    }
}
