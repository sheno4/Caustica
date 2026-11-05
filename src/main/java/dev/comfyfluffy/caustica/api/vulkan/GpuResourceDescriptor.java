package dev.comfyfluffy.caustica.api.vulkan;

/**
 * Borrowed local handle identifying a registered resource descriptor.
 *
 * <p>This is a borrowed descriptor view, not ownership of the image, buffer, or acceleration structure it
 * describes. A view obtained from a {@link dev.comfyfluffy.caustica.api.pass.PassFrame} is valid only while
 * that frame's pass callback records. Dispatch preparation resolves its {@link #index()} into an immutable
 * Vulkan descriptor set. The frame keeps both the descriptor entry and its resource alive until those
 * commands complete. Copying or caching the index does not extend that lifetime.
 *
 * <p>An extension never overwrites, retires, or destroys an engine-exposed descriptor view. Descriptors for
 * extension-owned resources are allocated through {@link GpuDescriptorHeap} instead.
 */
public interface GpuResourceDescriptor {
    /** Absolute resource slot resolved by the descriptor registry. */
    GpuDescriptorIndex.Resource index();
}
