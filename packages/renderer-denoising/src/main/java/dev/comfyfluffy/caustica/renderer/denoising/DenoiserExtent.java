package dev.comfyfluffy.caustica.renderer.denoising;

/** Render-resolution extent shared by a denoiser and all its images, before any display upscaling. */
public record DenoiserExtent(int width, int height) {
    public DenoiserExtent {
        if (width <= 0) throw new IllegalArgumentException("width must be positive");
        if (height <= 0) throw new IllegalArgumentException("height must be positive");
    }
}
