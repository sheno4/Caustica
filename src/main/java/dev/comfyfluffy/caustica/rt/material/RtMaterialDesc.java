package dev.comfyfluffy.caustica.rt.material;

/**
 * Immutable material description produced at the resource-epoch boundary, in OpenPBR vocabulary. Only
 * the uniform half lives here; anything varying per texel is in the canonical pages.
 */
public record RtMaterialDesc(
        int transport,
        Source source,
        int features,
        /** OpenPBR {@code specular_roughness}: perceptual. GGX alpha is its square, taken in the shader. */
        float specularRoughness,
        float baseMetalness,
        float specularIor,
        float transmissionWeight,
        EmissionSource emissionSource,
        /**
         * OpenPBR {@code emission_luminance}: final HDR emitting-surface luminance in cd/m², the
         * look-package baseline replaced by a source-authored {@code emission.luminance_cd_m2} value
         * when present. 0 when {@code emissionSource == NONE}. Applied uniformly regardless of source —
         * Authored, derived-mask, and geometry-uniform emission use the same baseline unless overridden.
         */
        float emissionLuminance,
        EmissionSummary emissionSummary,
        /**
         * Index of the registered {@code ISurfaceModel} implementation this material's description is
         * routed through, resolved from an authored name at load time. 0 is the built-in surface, which
         * is what everything gets unless an override names another.
         */
        int surfaceImplementation
) {
    public enum Source {
        OVERRIDE,
        AUTHORED_TEXTURE,
        DERIVED_TEXTURE,
        NEUTRAL
    }

    public enum EmissionSource {
        NONE,
        AUTHORED_MASK,
        DERIVED_MASK,
        GEOMETRY_UNIFORM
    }

    /** Normalized compiler output; per-primitive state light multiplies it when the source is state-gated. */
    public record EmissionSummary(float averageR, float averageG, float averageB,
                                  float integratedLuminance, float coverage) {
        public static final EmissionSummary NONE = new EmissionSummary(0, 0, 0, 0, 0);

        public boolean emissive() {
            return integratedLuminance > 0.0f && coverage > 0.0f;
        }
    }

    public RtMaterialDesc {
        if (source == null || emissionSource == null || emissionSummary == null) {
            throw new IllegalArgumentException("Material description enums/summary must be present");
        }
        if (surfaceImplementation < 0 || surfaceImplementation > 255) {
            // The compiled binding carries it in eight bits, so an out-of-range index would alias
            // another implementation rather than fail.
            throw new IllegalArgumentException("Surface implementation index out of range");
        }
        if (!finite01(specularRoughness) || !finite01(baseMetalness)
                || !Float.isFinite(specularIor) || specularIor <= 0.0f
                || !finite01(transmissionWeight)
                || !Float.isFinite(emissionLuminance) || emissionLuminance < 0.0f) {
            throw new IllegalArgumentException("Invalid physical material parameters");
        }
    }

    private static boolean finite01(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 1.0f;
    }
}
