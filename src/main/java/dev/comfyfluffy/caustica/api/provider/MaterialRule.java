package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.Objects;

/**
 * An ordered override of a renderer material selected by source resource and, optionally, geometry
 * resource. Sources submit the most specific rules first; the first matching rule owns a material.
 */
public record MaterialRule(ResourceId id, Match match, Parameters parameters) {
    public MaterialRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(parameters, "parameters");
    }

    /** Host-neutral resource names used by a scene adapter to identify the material being compiled. */
    public record Match(ResourceId material, ResourceId geometry) {
        public Match {
            Objects.requireNonNull(material, "material");
        }
    }

    /**
     * The OpenPBR subset currently accepted by the renderer. Null fields inherit the material source's
     * compiled value. A surface names a registered Slang {@code ISurfaceModel} implementation.
     */
    public record Parameters(Float specularRoughness, Float baseMetalness,
                             Float specularIor, Float transmissionWeight,
                             Float emissionLuminanceCdM2, ResourceId surface) {
        public Parameters {
            unit("specularRoughness", specularRoughness);
            unit("baseMetalness", baseMetalness);
            unit("transmissionWeight", transmissionWeight);
            if (specularIor != null && (!Float.isFinite(specularIor) || specularIor <= 0.0f)) {
                throw new IllegalArgumentException("specularIor must be positive");
            }
            if (emissionLuminanceCdM2 != null
                    && (!Float.isFinite(emissionLuminanceCdM2)
                    || emissionLuminanceCdM2 < 0.0f || emissionLuminanceCdM2 > 65504.0f)) {
                throw new IllegalArgumentException("emissionLuminanceCdM2 must be in [0,65504]");
            }
        }

        private static void unit(String name, Float value) {
            if (value != null && (!Float.isFinite(value) || value < 0.0f || value > 1.0f)) {
                throw new IllegalArgumentException(name + " must be in [0,1]");
            }
        }
    }

}
