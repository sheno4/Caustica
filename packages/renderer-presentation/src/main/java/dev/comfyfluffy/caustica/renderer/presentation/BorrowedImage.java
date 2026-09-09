package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.api.vulkan.GpuImage;

/** Immutable native image description; the caller keeps its handles alive through the consuming GPU work. */
public record BorrowedImage(long image, long view, int format, int width, int height) {
    public static BorrowedImage of(GpuImage image) {
        return new BorrowedImage(image.image(), image.view(), image.format(), image.width(), image.height());
    }
}
