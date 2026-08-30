package dev.comfyfluffy.caustica.renderer.denoising;

/** Temporal-history policy for one denoiser frame. */
public enum DenoiserReset {
    CONTINUE,
    CLEAR_AND_RESTART
}
