package dev.comfyfluffy.caustica.api.gpu;

/**
 * Suballocator for the one resource heap and one sampler heap the renderer binds to command buffers.
 *
 * <p>This is an allocator, not a resource registry. The renderer assigns byte ranges but never learns
 * which image, buffer, or sampler descriptor an extension writes there. The extension stores heap offsets
 * in its own GPU data and its own Slang reads them.
 */
public interface GpuDescriptorHeap {
    enum Kind {
        RESOURCE,
        SAMPLER
    }

    /** Descriptor byte sizes and alignments for laying out a range. */
    GpuDescriptorHeapProperties properties();

    /**
     * Allocate an aligned byte range from one bound heap. The range is uninitialized; write descriptor
     * bytes with Vulkan's descriptor-heap commands before making its offset reachable by a shader.
     */
    GpuDescriptorRange allocate(Kind kind, long byteSize, long byteAlignment, String label);
}
