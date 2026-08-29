package dev.comfyfluffy.caustica.api.vulkan;

/**
 * Allocation and shader-addressing facts for the renderer's bound Vulkan descriptor heaps.
 *
 * @param resourceDescriptorStride byte stride of one image, buffer, or acceleration-structure slot
 * @param samplerDescriptorStride byte stride of one sampler slot
 * @param resourceDescriptorCapacity number of application-visible resource slots
 * @param samplerDescriptorCapacity number of application-visible sampler slots
 * @param maximumResourceAllocation largest resource range accepted by one allocation
 * @param maximumSamplerAllocation largest sampler range accepted by one allocation
 * @param resourceHeapAlignment required byte alignment of the bound resource-heap device address
 * @param samplerHeapAlignment required byte alignment of the bound sampler-heap device address
 */
public record GpuDescriptorHeapProperties(
        long resourceDescriptorStride,
        long samplerDescriptorStride,
        int resourceDescriptorCapacity,
        int samplerDescriptorCapacity,
        int maximumResourceAllocation,
        int maximumSamplerAllocation,
        long resourceHeapAlignment,
        long samplerHeapAlignment
) {
    public GpuDescriptorHeapProperties {
        requirePositive(resourceDescriptorStride, "resourceDescriptorStride");
        requirePositive(samplerDescriptorStride, "samplerDescriptorStride");
        requirePositive(resourceDescriptorCapacity, "resourceDescriptorCapacity");
        requirePositive(samplerDescriptorCapacity, "samplerDescriptorCapacity");
        requireAllocation(maximumResourceAllocation, resourceDescriptorCapacity, "maximumResourceAllocation");
        requireAllocation(maximumSamplerAllocation, samplerDescriptorCapacity, "maximumSamplerAllocation");
        requirePowerOfTwo(resourceHeapAlignment, "resourceHeapAlignment");
        requirePowerOfTwo(samplerHeapAlignment, "samplerHeapAlignment");
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
