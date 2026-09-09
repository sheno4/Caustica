package dev.comfyfluffy.caustica.renderer.denoising;

import java.util.Objects;

/**
 * Borrowed Vulkan image and its layout at the denoiser boundary. The caller keeps the image alive
 * until submitted work completes; this description does not retain or synchronize it.
 */
public record DenoiserImage(long image, int format, int layout, DenoiserExtent extent) {
    public DenoiserImage {
        if (image == 0L) throw new IllegalArgumentException("image must be a non-null Vulkan handle");
        if (format == 0) throw new IllegalArgumentException("format must not be VK_FORMAT_UNDEFINED");
        Objects.requireNonNull(extent, "extent");
    }
}
