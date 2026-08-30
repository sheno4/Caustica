package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.api.vulkan.GpuImage;

/** Immutable description of a Vulkan image borrowed from the presentation host. */
public record BorrowedImage(long image, long view, int format, int width, int height) {
    public static BorrowedImage of(GpuImage image) {
        return new BorrowedImage(image.image(), image.view(), image.format(), image.width(), image.height());
    }
}
