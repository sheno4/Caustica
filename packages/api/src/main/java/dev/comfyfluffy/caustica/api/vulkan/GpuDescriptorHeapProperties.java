package dev.comfyfluffy.caustica.api.vulkan;

/**
 * Allocation and shader-addressing facts for the renderer's bound Vulkan descriptor heaps.
 *
 * @param resourceDescriptorStrideBytes byte stride of one image, buffer, or acceleration-structure slot
 * @param maximumResourceAllocation largest resource range accepted by one allocation
 * @param maximumSamplerAllocation largest sampler range accepted by one allocation
 */
public record GpuDescriptorHeapProperties(
        long resourceDescriptorStrideBytes,
        int maximumResourceAllocation,
        int maximumSamplerAllocation
) {
    public GpuDescriptorHeapProperties {
        requirePositive(resourceDescriptorStrideBytes, "resourceDescriptorStrideBytes");
        requirePositive(maximumResourceAllocation, "maximumResourceAllocation");
        requirePositive(maximumSamplerAllocation, "maximumSamplerAllocation");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

}
