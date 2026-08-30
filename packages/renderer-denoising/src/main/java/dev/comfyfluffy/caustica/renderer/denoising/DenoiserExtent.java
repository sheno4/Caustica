package dev.comfyfluffy.caustica.renderer.denoising;

/** Full-resolution image extent used by a denoiser instance and all of its frame resources. */
public record DenoiserExtent(int width, int height) {
    public DenoiserExtent {
        if (width <= 0) throw new IllegalArgumentException("width must be positive");
        if (height <= 0) throw new IllegalArgumentException("height must be positive");
    }
}
