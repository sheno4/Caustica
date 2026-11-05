/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.api.vulkan;

import dev.comfyfluffy.cloudly.api.resource.ResourceOwner;

/**
 * An independent claim that keeps an image, its view and descriptors alive until closed.
 * Retaining returns another claim; closing this one does not invalidate other retained claims.
 */
public interface OwnedGpuImage extends GpuImage, ResourceOwner {
    @Override OwnedGpuImage retain();
}
