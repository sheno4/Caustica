package dev.comfyfluffy.caustica.api.vulkan;

/**
 * Allocation and shader-addressing facts for the renderer's bound Vulkan descriptor heaps.
 *
 * @param resourceDescriptorStrideBytes byte stride of one image, buffer, or acceleration-structure slot
 * @param samplerDescriptorStrideBytes byte stride of one sampler slot
 * @param resourceDescriptorCapacity number of application-visible resource slots
 * @param samplerDescriptorCapacity number of application-visible sampler slots
 * @param maximumResourceAllocation largest resource range accepted by one allocation
 * @param maximumSamplerAllocation largest sampler range accepted by one allocation
 * @param resourceHeapAlignmentBytes required byte alignment of the bound resource-heap device address
 * @param samplerHeapAlignmentBytes required byte alignment of the bound sampler-heap device address
 */
public record GpuDescriptorHeapProperties(
        long resourceDescriptorStrideBytes,
        long samplerDescriptorStrideBytes,
        int resourceDescriptorCapacity,
        int samplerDescriptorCapacity,
        int maximumResourceAllocation,
        int maximumSamplerAllocation,
        long resourceHeapAlignmentBytes,
        long samplerHeapAlignmentBytes
) {
    public GpuDescriptorHeapProperties {
        requirePositive(resourceDescriptorStrideBytes, "resourceDescriptorStrideBytes");
        requirePositive(samplerDescriptorStrideBytes, "samplerDescriptorStrideBytes");
        requirePositive(resourceDescriptorCapacity, "resourceDescriptorCapacity");
        requirePositive(samplerDescriptorCapacity, "samplerDescriptorCapacity");
        requireAllocation(maximumResourceAllocation, resourceDescriptorCapacity, "maximumResourceAllocation");
        requireAllocation(maximumSamplerAllocation, samplerDescriptorCapacity, "maximumSamplerAllocation");
        requirePowerOfTwo(resourceHeapAlignmentBytes, "resourceHeapAlignmentBytes");
        requirePowerOfTwo(samplerHeapAlignmentBytes, "samplerHeapAlignmentBytes");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireAllocation(int value, int capacity, String name) {
        if (value <= 0 || value > capacity) {
            throw new IllegalArgumentException(name + " must be positive and no greater than capacity");
        }
    }

    private static void requirePowerOfTwo(long value, String name) {
        if (value <= 0 || (value & (value - 1)) != 0) {
            throw new IllegalArgumentException(name + " must be a positive power of two");
        }
    }
}
