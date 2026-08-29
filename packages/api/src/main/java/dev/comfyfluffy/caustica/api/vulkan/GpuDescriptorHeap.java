package dev.comfyfluffy.caustica.api.vulkan;

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
    /** Vulkan heap properties for pipeline and raw-resource setup. */
    GpuDescriptorHeapProperties properties();

    /**
     * Allocate consecutive shader-visible resource slots. Pass shaders own their compilation; SPIR-V
     * descriptor-heap access must target Vulkan 1.4 with {@code spvDescriptorHeapEXT} and
     * {@link GpuDescriptorHeapProperties#resourceDescriptorStrideBytes()}. The range is uninitialized until
     * written through {@link #writer()}.
     */
    GpuDescriptorRange<GpuDescriptorIndex.Resource> allocateResources(int descriptorCount, String label);

    /**
     * Allocate consecutive shader-visible sampler slots using
     * {@link GpuDescriptorHeapProperties#samplerDescriptorStrideBytes()}.
     */
    GpuDescriptorRange<GpuDescriptorIndex.Sampler> allocateSamplers(int descriptorCount, String label);

    /** Encoder that writes valid descriptor bytes and flushes non-coherent heap memory before returning. */
    GpuDescriptorWriter writer();
}
