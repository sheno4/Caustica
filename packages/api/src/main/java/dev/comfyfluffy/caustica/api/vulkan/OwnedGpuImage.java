package dev.comfyfluffy.caustica.api.vulkan;

import dev.comfyfluffy.caustica.api.resource.ResourceOwner;

/** An image claim that keeps its native image, view and descriptors alive until closed. */
public interface OwnedGpuImage extends GpuImage, ResourceOwner {
    @Override OwnedGpuImage retain();
}
