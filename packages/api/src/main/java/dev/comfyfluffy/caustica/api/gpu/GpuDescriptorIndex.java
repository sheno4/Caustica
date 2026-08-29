package dev.comfyfluffy.caustica.api.gpu;

/** Shader-visible index in one of the two descriptor heaps bound by the renderer. */
public sealed interface GpuDescriptorIndex {
    int value();

    /**
     * Index interpreted by Slang's {@code ResourceDescriptorHeap}.
     *
     * @param value absolute heap index
     */
    record Resource(int value) implements GpuDescriptorIndex {
        public Resource {
            requireNonNegative(value);
        }
    }

    /**
     * Index interpreted by Slang's {@code SamplerDescriptorHeap}.
     *
     * @param value absolute heap index
     */
    record Sampler(int value) implements GpuDescriptorIndex {
        public Sampler {
            requireNonNegative(value);
        }
    }

    private static void requireNonNegative(int value) {
        if (value < 0) throw new IllegalArgumentException("descriptor index must be non-negative");
    }
}
