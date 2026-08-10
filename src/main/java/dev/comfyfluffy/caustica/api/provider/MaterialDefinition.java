package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.Objects;

/**
 * A named, textureless OpenPBR material that geometry sources can reference through a stable handle.
 * Base color is scene-linear ACEScg. Roughness is OpenPBR perceptual roughness, not GGX alpha. A null
 * surface selects the renderer's built-in OpenPBR surface implementation.
 */
public record MaterialDefinition(MaterialHandle handle, float baseColorR, float baseColorG, float baseColorB,
                                 float specularRoughness, float baseMetalness, float specularIor,
                                 float transmissionWeight, ResourceId surface) {
    public MaterialDefinition {
        Objects.requireNonNull(handle, "handle");
        unit("baseColorR", baseColorR);
        unit("baseColorG", baseColorG);
        unit("baseColorB", baseColorB);
        unit("specularRoughness", specularRoughness);
        unit("baseMetalness", baseMetalness);
        unit("transmissionWeight", transmissionWeight);
        if (!Float.isFinite(specularIor) || specularIor <= 0.0f) {
            throw new IllegalArgumentException("specularIor must be positive");
        }
    }

    public ResourceId id() {
        return handle.id();
    }

    private static void unit(String name, float value) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be in [0,1]");
        }
    }
}
