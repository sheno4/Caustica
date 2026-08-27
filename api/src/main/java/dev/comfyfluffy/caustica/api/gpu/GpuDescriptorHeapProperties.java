package dev.comfyfluffy.caustica.api.gpu;

/**
 * Descriptor sizes and alignments, in bytes, for the renderer's bound Vulkan descriptor heaps.
 *
 * @param samplerDescriptorSize size of one sampler descriptor
 * @param imageDescriptorSize size of one image descriptor
 * @param bufferDescriptorSize size of one buffer or acceleration-structure descriptor
 * @param samplerDescriptorAlignment required alignment of a sampler descriptor
 * @param imageDescriptorAlignment required alignment of an image descriptor
 * @param bufferDescriptorAlignment required alignment of a buffer or acceleration-structure descriptor
 * @param maxPushDataSize maximum bytes accepted by {@code vkCmdPushDataEXT}
 */
public record GpuDescriptorHeapProperties(
        long samplerDescriptorSize,
        long imageDescriptorSize,
        long bufferDescriptorSize,
        long samplerDescriptorAlignment,
        long imageDescriptorAlignment,
        long bufferDescriptorAlignment,
        long maxPushDataSize
) {
    public GpuDescriptorHeapProperties {
        requirePositive(samplerDescriptorSize, "samplerDescriptorSize");
        requirePositive(imageDescriptorSize, "imageDescriptorSize");
        requirePositive(bufferDescriptorSize, "bufferDescriptorSize");
        requirePowerOfTwo(samplerDescriptorAlignment, "samplerDescriptorAlignment");
        requirePowerOfTwo(imageDescriptorAlignment, "imageDescriptorAlignment");
        requirePowerOfTwo(bufferDescriptorAlignment, "bufferDescriptorAlignment");
        requirePositive(maxPushDataSize, "maxPushDataSize");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePowerOfTwo(long value, String name) {
        if (value <= 0 || Long.bitCount(value) != 1) {
            throw new IllegalArgumentException(name + " must be a positive power of two");
        }
    }
}
