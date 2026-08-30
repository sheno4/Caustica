package dev.comfyfluffy.caustica.renderer.denoising;

/** Renderer-level presentation route selected independently of a vendor backend. */
public enum DenoiserRoute {
    RAW,
    TEMPORAL_DENOISER,
    RAY_RECONSTRUCTION
}
