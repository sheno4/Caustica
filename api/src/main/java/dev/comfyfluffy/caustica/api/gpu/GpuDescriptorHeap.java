package dev.comfyfluffy.caustica.api.gpu;

/**
 * Suballocator for the one resource heap and one sampler heap the renderer binds to command buffers.
 *
 * <p>This is an allocator, not a resource registry. The renderer assigns descriptor slots and encodes requested
 * descriptors but retains no descriptor ownership or resource mapping. The extension stores heap indices
 * in its own GPU data and its own Slang reads them.
 * Allocation and descriptor writing are thread-safe; callers still synchronize publication of indices in
 * their own data.
 */
public interface GpuDescriptorHeap {
    enum Kind {
        RESOURCE,
        SAMPLER
    }

    /** Vulkan heap properties for pipeline and raw-resource setup. */
    GpuDescriptorHeapProperties properties();

    /**
     * Allocate consecutive shader-visible descriptor slots from one bound heap. Resource slots use
     * {@link GpuDescriptorHeapProperties#resourceDescriptorStride()}, and sampler slots use
     * {@link GpuDescriptorHeapProperties#samplerDescriptorStride()}. Pass shaders own their compilation;
     * SPIR-V descriptor-heap access must target Vulkan 1.4 with {@code spvDescriptorHeapEXT} and those
     * strides. The range is uninitialized until written through {@link #writer()}.
     */
    GpuDescriptorRange allocate(Kind kind, int descriptorCount, String label);

    /** Encoder that writes valid descriptor bytes and flushes non-coherent heap memory before returning. */
    GpuDescriptorWriter writer();
}
