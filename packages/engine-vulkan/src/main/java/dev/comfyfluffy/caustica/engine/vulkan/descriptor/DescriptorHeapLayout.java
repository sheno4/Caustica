package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;

/** Byte and slot layout for one bound descriptor heap, including its implementation-reserved prefix. */
public record DescriptorHeapLayout(
        DescriptorHeapKind kind,
        long descriptorStrideBytes,
        long heapAddressAlignmentBytes,
        long reservedRangeBytes,
        long heapSizeBytes,
        int firstApplicationIndex,
        int applicationCapacity,
        int maximumAllocation
) {
    public static DescriptorHeapLayout create(
            DescriptorHeapKind kind,
            long descriptorStrideBytes,
            long heapAddressAlignmentBytes,
            long minimumReservedRangeBytes,
            long maximumHeapSizeBytes,
            int applicationCapacity,
            int maximumAllocation
    ) {
        if (descriptorStrideBytes <= 0) throw new IllegalArgumentException("descriptor stride must be positive");
        requirePowerOfTwo(heapAddressAlignmentBytes, "heap address alignment");
        if (minimumReservedRangeBytes < 0) {
            throw new IllegalArgumentException("minimum reserved range must not be negative");
        }
        if (maximumHeapSizeBytes <= 0) throw new IllegalArgumentException("maximum heap size must be positive");
        if (applicationCapacity <= 0) throw new IllegalArgumentException("application capacity must be positive");
        if (maximumAllocation <= 0 || maximumAllocation > applicationCapacity) {
            throw new IllegalArgumentException("maximum allocation must be positive and no greater than capacity");
        }

        long reservedBytes = alignUp(minimumReservedRangeBytes, descriptorStrideBytes);
        long reservedSlots = reservedBytes / descriptorStrideBytes;
        if (reservedSlots > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("reserved range exceeds the shader-visible index space");
        }
        long applicationBytes = multiplyExact(descriptorStrideBytes, applicationCapacity, "application heap bytes overflow");
        long heapBytes = addExact(reservedBytes, applicationBytes, "descriptor heap size overflow");
        if (heapBytes > maximumHeapSizeBytes) {
            throw new IllegalArgumentException("descriptor heap requires " + heapBytes
                    + " bytes but the device limit is " + maximumHeapSizeBytes);
        }
        long lastIndexExclusive = addExact(reservedSlots, applicationCapacity, "descriptor index space overflow");
        if (lastIndexExclusive > (long) Integer.MAX_VALUE + 1L) {
            throw new IllegalArgumentException("descriptor heap exceeds the non-negative 32-bit index space");
        }
        return new DescriptorHeapLayout(kind, descriptorStrideBytes, heapAddressAlignmentBytes,
                reservedBytes, heapBytes, (int) reservedSlots, applicationCapacity, maximumAllocation);
    }

    public DescriptorHeapLayout {
        if (kind == null) throw new NullPointerException("kind");
        if (descriptorStrideBytes <= 0) throw new IllegalArgumentException("descriptor stride must be positive");
        requirePowerOfTwo(heapAddressAlignmentBytes, "heap address alignment");
        if (reservedRangeBytes < 0 || reservedRangeBytes % descriptorStrideBytes != 0) {
            throw new IllegalArgumentException("reserved range must contain whole descriptor slots");
        }
        if (applicationCapacity <= 0) throw new IllegalArgumentException("application capacity must be positive");
        if (maximumAllocation <= 0 || maximumAllocation > applicationCapacity) {
            throw new IllegalArgumentException("maximum allocation must be positive and no greater than capacity");
        }
        if (firstApplicationIndex != reservedRangeBytes / descriptorStrideBytes) {
            throw new IllegalArgumentException("first application index does not follow the reserved range");
        }
        long requiredBytes = addExact(reservedRangeBytes,
                multiplyExact(descriptorStrideBytes, applicationCapacity, "application heap bytes overflow"),
                "descriptor heap size overflow");
        if (heapSizeBytes != requiredBytes) {
            throw new IllegalArgumentException("heap size does not match its reserved and application ranges");
        }
    }

    public long applicationOffsetBytes() {
        return reservedRangeBytes;
    }

    public long byteOffset(int absoluteIndex) {
        if (absoluteIndex < firstApplicationIndex
                || (long) absoluteIndex >= (long) firstApplicationIndex + applicationCapacity) {
            throw new IllegalArgumentException("descriptor index is outside the application-visible range");
        }
        return multiplyExact(descriptorStrideBytes, absoluteIndex, "descriptor byte offset overflow");
    }

    public void validateStorage(VulkanDeviceAddressRange storage) {
        if (!storage.address().isAlignedTo(heapAddressAlignmentBytes)) {
            throw new IllegalArgumentException("heap device address is misaligned");
        }
        if (storage.byteSize() < heapSizeBytes) {
            throw new IllegalArgumentException("heap storage is smaller than the required layout");
        }
    }

    private static long alignUp(long value, long alignment) {
        long remainder = value % alignment;
        return remainder == 0 ? value : addExact(value, alignment - remainder, "reserved range alignment overflow");
    }

    private static long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }

    private static long multiplyExact(long left, long right, String message) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }

    private static void requirePowerOfTwo(long value, String name) {
        if (value <= 0 || (value & (value - 1)) != 0) {
            throw new IllegalArgumentException(name + " must be a positive power of two");
        }
    }
}
