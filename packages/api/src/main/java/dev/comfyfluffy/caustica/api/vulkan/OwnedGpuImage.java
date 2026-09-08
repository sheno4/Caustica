package dev.comfyfluffy.caustica.api.vulkan;

import dev.comfyfluffy.caustica.api.resource.ResourceOwner;

/**
 * An independent claim that keeps an image, its view and descriptors alive until closed.
 * Retaining returns another claim; closing this one does not invalidate other retained claims.
 */
public interface OwnedGpuImage extends GpuImage, ResourceOwner {
    @Override OwnedGpuImage retain();
}
