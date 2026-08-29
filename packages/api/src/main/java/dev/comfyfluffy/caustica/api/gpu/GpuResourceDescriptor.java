package dev.comfyfluffy.caustica.api.gpu;

/**
 * Shader-visible entry in the renderer's resource descriptor heap.
 *
 * <p>This is a borrowed descriptor view, not ownership of the image, buffer, or acceleration structure it
 * describes. A view obtained from a {@link dev.comfyfluffy.caustica.api.pass.PassFrame} is valid only while
 * that frame's pass callback records, but commands recorded there may use its {@link #index()} after the
 * callback returns. The renderer keeps both the immutable descriptor entry and its resource alive until
 * those commands complete. Copying or caching the index does not extend that lifetime.
 *
 * <p>An extension never overwrites, retires, or destroys an engine-exposed descriptor view. Descriptors for
 * extension-owned resources are allocated through {@link GpuDescriptorHeap} instead.
 */
public interface GpuResourceDescriptor {
    /** Absolute slot in the resource heap, used directly by {@code ResourceDescriptorHeap}. */
    GpuDescriptorIndex.Resource index();
}
