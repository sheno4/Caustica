package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.engine.material.EmissionFootprint;

/** Renderer-resolved CPU semantics for a source-neutral material reference. */
public record MaterialAnalysis(EmissionSource emissionSource,
                               float emissionLuminanceCdM2,
                               EmissionFootprint emissionFootprint,
                               int emissionFootprintResolution) {
    public MaterialAnalysis {
        java.util.Objects.requireNonNull(emissionSource, "emissionSource");
        if (!Float.isFinite(emissionLuminanceCdM2) || emissionLuminanceCdM2 < 0.0f) {
            throw new IllegalArgumentException("emission luminance must be finite and non-negative");
        }
        if (emissionFootprintResolution <= 0) {
            throw new IllegalArgumentException("emission footprint resolution must be positive");
        }
    }

    public enum EmissionSource {
        NONE,
        AUTHORED_MASK,
        DERIVED_MASK,
        GEOMETRY_UNIFORM
    }
}
