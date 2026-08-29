package dev.comfyfluffy.caustica.api.vulkan;

/** Borrowed shader-visible descriptor whose resource is an image. */
public interface GpuImageDescriptor extends GpuResourceDescriptor {
    /** The image interpretation encoded in this immutable descriptor entry. */
    GpuImageDescriptorKind kind();
}
