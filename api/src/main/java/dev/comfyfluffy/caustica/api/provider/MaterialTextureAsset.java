package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.Objects;

/**
 * Optional semantic texture streams for a named OpenPBR material in the current resource epoch.
 * {@code surfaceParameters}, {@code normalMap}, and {@code emissionMask} declare which values written by
 * {@link MaterialTextureImage#readOpenPbr(int, int, OpenPbrTextureTexel)} are authored. Uniform emission
 * luminance is used only when this asset is submitted without a matching {@link MaterialDefinition}.
 */
public record MaterialTextureAsset(ResourceId material, MaterialTextureKind kind,
                                   int width, int height,
                                   MaterialTextureSource texture,
                                   MaterialUv albedoUv,
                                   boolean surfaceParameters,
                                   boolean normalMap,
                                   boolean emissionMask,
                                   OpenPbrColorBinding subsurfaceColorBinding,
                                   OpenPbrColorBinding emissionColorBinding,
                                   float dielectricIor,
                                   float uniformEmissionLuminanceCdM2) {
    public MaterialTextureAsset {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(albedoUv, "albedoUv");
        Objects.requireNonNull(subsurfaceColorBinding, "subsurfaceColorBinding");
        Objects.requireNonNull(emissionColorBinding, "emissionColorBinding");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("material dimensions must be positive");
        if (!Float.isFinite(dielectricIor) || dielectricIor <= 0.0f) {
            throw new IllegalArgumentException("dielectricIor must be positive");
        }
        if (!Float.isFinite(uniformEmissionLuminanceCdM2) || uniformEmissionLuminanceCdM2 < 0.0f) {
            throw new IllegalArgumentException("uniform emission luminance must be finite and non-negative");
        }
    }
}
