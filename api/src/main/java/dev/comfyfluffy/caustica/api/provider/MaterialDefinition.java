package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.Objects;

/**
 * A named OpenPBR material that geometry sources reference through a stable handle. Colors are
 * scene-linear ACEScg. Roughness is OpenPBR perceptual roughness, not GGX alpha. A null surface selects
 * the renderer's built-in implementation. Topology is structural and independent of transmission weight.
 * When {@code textures} is present, sampled base color, roughness, metalness, subsurface weight, and
 * emission are multiplied by their corresponding uniform values in this declaration.
 */
public record MaterialDefinition(MaterialHandle handle, float baseColorR, float baseColorG, float baseColorB,
                                 float specularRoughness, float baseMetalness, float specularIor,
                                 float transmissionWeight,
                                 float transmissionColorR, float transmissionColorG, float transmissionColorB,
                                 float subsurfaceWeight,
                                 float subsurfaceColorR, float subsurfaceColorG, float subsurfaceColorB,
                                 float subsurfaceScatterAnisotropy,
                                 float emissionColorR, float emissionColorG, float emissionColorB,
                                 float emissionLuminanceCdM2,
                                 MaterialTopology topology, ResourceId surface, float alphaCutoff,
                                 MaterialTextureAsset textures) {
    public MaterialDefinition(MaterialHandle handle, float baseColorR, float baseColorG, float baseColorB,
                              float specularRoughness, float baseMetalness, float specularIor,
                              float transmissionWeight, MaterialTopology topology, ResourceId surface) {
        this(handle, baseColorR, baseColorG, baseColorB, specularRoughness, baseMetalness, specularIor,
                transmissionWeight, 1.0f, 1.0f, 1.0f,
                0.0f, 0.8f, 0.8f, 0.8f, 0.0f,
                1.0f, 1.0f, 1.0f, 0.0f, topology, surface, 0.5f, null);
    }

    public MaterialDefinition {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(topology, "topology");
        unit("baseColorR", baseColorR);
        unit("baseColorG", baseColorG);
        unit("baseColorB", baseColorB);
        unit("specularRoughness", specularRoughness);
        unit("baseMetalness", baseMetalness);
        unit("transmissionWeight", transmissionWeight);
        unit("transmissionColorR", transmissionColorR);
        unit("transmissionColorG", transmissionColorG);
        unit("transmissionColorB", transmissionColorB);
        unit("subsurfaceWeight", subsurfaceWeight);
        unit("subsurfaceColorR", subsurfaceColorR);
        unit("subsurfaceColorG", subsurfaceColorG);
        unit("subsurfaceColorB", subsurfaceColorB);
        if (!Float.isFinite(subsurfaceScatterAnisotropy)
                || subsurfaceScatterAnisotropy < -1.0f || subsurfaceScatterAnisotropy > 1.0f) {
            throw new IllegalArgumentException("subsurfaceScatterAnisotropy must be in [-1,1]");
        }
        nonNegative("emissionColorR", emissionColorR);
        nonNegative("emissionColorG", emissionColorG);
        nonNegative("emissionColorB", emissionColorB);
        if (!Float.isFinite(emissionLuminanceCdM2)
                || emissionLuminanceCdM2 < 0.0f || emissionLuminanceCdM2 > 65504.0f) {
            throw new IllegalArgumentException("emissionLuminanceCdM2 must be in [0,65504]");
        }
        unit("alphaCutoff", alphaCutoff);
        if (!Float.isFinite(specularIor) || specularIor <= 0.0f) {
            throw new IllegalArgumentException("specularIor must be positive");
        }
        if (textures != null && !textures.material().equals(handle.id())) {
            throw new IllegalArgumentException("material texture asset must use the definition handle id");
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

    private static void nonNegative(String name, float value) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
