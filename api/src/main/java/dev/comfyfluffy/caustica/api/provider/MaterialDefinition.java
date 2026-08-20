package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.Objects;

/**
 * A named OpenPBR material that geometry sources reference through a stable handle. Colors are
 * scene-linear ACEScg. Roughness is OpenPBR perceptual roughness, not GGX alpha. The surface identifier
 * must name an implementation registered for the active resource epoch; its paired coverage implementation
 * evaluates alpha. Topology is structural and independent of transmission weight. Provider data is opaque
 * to the engine and interpreted only by the selected surface implementation.
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
                                 MaterialProviderData providerData) {
    public MaterialDefinition(MaterialHandle handle, float baseColorR, float baseColorG, float baseColorB,
                              float specularRoughness, float baseMetalness, float specularIor,
                              float transmissionWeight, MaterialTopology topology, ResourceId surface) {
        this(handle, baseColorR, baseColorG, baseColorB, specularRoughness, baseMetalness, specularIor,
                transmissionWeight, 1.0f, 1.0f, 1.0f,
                0.0f, 0.8f, 0.8f, 0.8f, 0.0f,
                1.0f, 1.0f, 1.0f, 0.0f, topology, surface, 0.5f,
                MaterialProviderData.ZERO);
    }

    public MaterialDefinition(MaterialHandle handle, float baseColorR, float baseColorG, float baseColorB,
                              float specularRoughness, float baseMetalness, float specularIor,
                              float transmissionWeight,
                              float transmissionColorR, float transmissionColorG, float transmissionColorB,
                              float subsurfaceWeight,
                              float subsurfaceColorR, float subsurfaceColorG, float subsurfaceColorB,
                              float subsurfaceScatterAnisotropy,
                              float emissionColorR, float emissionColorG, float emissionColorB,
                              float emissionLuminanceCdM2,
                              MaterialTopology topology, ResourceId surface, float alphaCutoff) {
        this(handle, baseColorR, baseColorG, baseColorB, specularRoughness, baseMetalness, specularIor,
                transmissionWeight, transmissionColorR, transmissionColorG, transmissionColorB,
                subsurfaceWeight, subsurfaceColorR, subsurfaceColorG, subsurfaceColorB,
                subsurfaceScatterAnisotropy, emissionColorR, emissionColorG, emissionColorB,
                emissionLuminanceCdM2, topology, surface, alphaCutoff,
                MaterialProviderData.ZERO);
    }

    public MaterialDefinition {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(providerData, "providerData");
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
