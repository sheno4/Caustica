package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.Objects;

/**
 * Material-texture declaration for one named material in the current resource epoch. The resource declares
 * placement and authored OpenPBR streams; {@link #analysisSource()} supplies their canonical CPU pixels.
 */
public record MaterialTextureResource(ResourceId material, MaterialTextureKind kind,
                                      MaterialTextureAnalysisSource analysisSource,
                                      MaterialUv albedoUv,
                                      boolean surfaceParameters,
                                      boolean normalMap,
                                      boolean emissionMask,
                                      OpenPbrColorBinding subsurfaceColorBinding,
                                      OpenPbrColorBinding emissionColorBinding,
                                      float dielectricIor,
                                      float uniformEmissionLuminanceCdM2) {
    public MaterialTextureResource {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(analysisSource, "analysisSource");
        Objects.requireNonNull(albedoUv, "albedoUv");
        Objects.requireNonNull(subsurfaceColorBinding, "subsurfaceColorBinding");
        Objects.requireNonNull(emissionColorBinding, "emissionColorBinding");
        if (!Float.isFinite(dielectricIor) || dielectricIor <= 0.0f) {
            throw new IllegalArgumentException("dielectricIor must be positive");
        }
        if (!Float.isFinite(uniformEmissionLuminanceCdM2) || uniformEmissionLuminanceCdM2 < 0.0f) {
            throw new IllegalArgumentException("uniform emission luminance must be finite and non-negative");
        }
    }
}
