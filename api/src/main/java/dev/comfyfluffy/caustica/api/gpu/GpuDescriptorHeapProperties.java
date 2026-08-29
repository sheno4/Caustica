package dev.comfyfluffy.caustica.api.gpu;

/**
 * Effective shader-visible descriptor strides, in bytes, for the renderer's bound Vulkan descriptor heaps.
 *
 * @param resourceDescriptorStride byte stride of one image, buffer, or acceleration-structure slot
 * @param samplerDescriptorStride byte stride of one sampler slot
 */
public record GpuDescriptorHeapProperties(
        long resourceDescriptorStride,
        long samplerDescriptorStride
) {
    public GpuDescriptorHeapProperties {
        requirePositive(resourceDescriptorStride, "resourceDescriptorStride");
        requirePositive(samplerDescriptorStride, "samplerDescriptorStride");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
