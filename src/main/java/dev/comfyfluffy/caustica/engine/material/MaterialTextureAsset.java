package dev.comfyfluffy.caustica.engine.material;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.Objects;

/** One source material and its optional LabPBR companion images for the current resource epoch. */
public record MaterialTextureAsset(ResourceId material, MaterialTextureKind kind,
                                   int width, int height,
                                   MaterialImageSource albedo,
                                   MaterialImageSource labPbrSpecular,
                                   MaterialImageSource labPbrNormal,
                                   MaterialUv albedoUv,
                                   boolean inferEmissionMask,
                                   float dielectricIor) {
    public MaterialTextureAsset {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(albedo, "albedo");
        Objects.requireNonNull(albedoUv, "albedoUv");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("material dimensions must be positive");
        if (!Float.isFinite(dielectricIor) || dielectricIor <= 0.0f) {
            throw new IllegalArgumentException("dielectricIor must be positive");
        }
    }

    public boolean sharedAtlas() {
        return kind == MaterialTextureKind.SHARED_ATLAS;
    }
}
