package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserSignalEncoding;

import java.util.Objects;

/** Immutable selection of the renderer's single reconstruction or denoising route. */
public record RtDenoisingSettings(DenoiserRoute route, DenoiserSignalEncoding signalEncoding) {
    public RtDenoisingSettings {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(signalEncoding, "signalEncoding");
    }

    public static RtDenoisingSettings rayReconstruction() {
        return new RtDenoisingSettings(DenoiserRoute.RAY_RECONSTRUCTION,
                DenoiserSignalEncoding.LINEAR_RGB_ABSOLUTE_HIT_DISTANCE);
    }
}
