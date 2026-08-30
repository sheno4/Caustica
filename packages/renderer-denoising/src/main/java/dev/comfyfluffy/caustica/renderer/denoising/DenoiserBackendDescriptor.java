package dev.comfyfluffy.caustica.renderer.denoising;

import java.util.Objects;

/** Immutable resource and signal contract for one backend lifetime. */
public record DenoiserBackendDescriptor(
        DenoiserExtent extent,
        DenoiserSignalEncoding signalEncoding) {
    public DenoiserBackendDescriptor {
        Objects.requireNonNull(extent, "extent");
        Objects.requireNonNull(signalEncoding, "signalEncoding");
    }
}
