package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.OpenPbrMaterialDefaults;
import dev.comfyfluffy.caustica.api.provider.OpenPbrMaterialProfile;

/** Pure CPU material-description compilation used while preparing a registry epoch. */
final class MaterialRegistryCompiler {
    static final OpenPbrMaterialProfile[] TEXTURE_PROFILES = {
            OpenPbrMaterialProfile.ROUGH_DIELECTRIC, OpenPbrMaterialProfile.CONDUCTOR,
            OpenPbrMaterialProfile.SMOOTH_DIELECTRIC, OpenPbrMaterialProfile.POLISHED_DIELECTRIC,
            OpenPbrMaterialProfile.MEDIUM_ROUGH_DIELECTRIC};

    private MaterialRegistryCompiler() { }

    static int variantCount() { return TEXTURE_PROFILES.length * 2 * 2; }

    static int index(OpenPbrMaterialProfile profile, MaterialTopology topology, boolean emitting) {
        int profileIndex = -1;
        for (int i = 0; i < TEXTURE_PROFILES.length; i++) if (TEXTURE_PROFILES[i].equals(profile)) profileIndex = i;
        if (profileIndex < 0) throw new IllegalArgumentException("Unsupported texture material profile " + profile);
        int topologyIndex = topology == MaterialTopology.SURFACE ? 0 : 1;
        return (profileIndex * 2 + topologyIndex) * 2 + (emitting ? 1 : 0);
    }

    static int transport(MaterialTopology topology) {
        return topology == MaterialTopology.SURFACE ? RtMaterialRegistry.TRANSPORT_SURFACE
                : RtMaterialRegistry.TRANSPORT_MEDIUM_BOUNDARY;
    }

    static RtMaterialDesc textureDescription(int transport, int features, OpenPbrMaterialProfile profile,
                                             boolean emitting, boolean neutral,
                                             float dielectricIor, float defaultEmissionLuminance) {
        boolean medium = transport == RtMaterialRegistry.TRANSPORT_MEDIUM_BOUNDARY;
        float roughness = medium ? OpenPbrMaterialDefaults.TRANSMISSIVE_SPECULAR_ROUGHNESS
                : profile.specularRoughness();
        float metalness = medium ? 0 : profile.baseMetalness();
        if ((features & RtMaterialRegistry.FEATURE_SPEC) != 0) {
            roughness = 1.0f;
            metalness = 1.0f;
        }
        float ior = medium ? dielectricIor : OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR;
        float transmission = medium ? 1 : 0;
        boolean authored = (features & (RtMaterialRegistry.FEATURE_SPEC | RtMaterialRegistry.FEATURE_NORMAL)) != 0;
        RtMaterialDesc.Source source = neutral ? RtMaterialDesc.Source.NEUTRAL
                : authored ? RtMaterialDesc.Source.AUTHORED_TEXTURE : RtMaterialDesc.Source.DERIVED_TEXTURE;
        boolean emissionCapable = emitting || (features & RtMaterialRegistry.FEATURE_EMISSION_MASK) != 0;
        float luminance = emissionCapable ? defaultEmissionLuminance : 0.0f;
        return new RtMaterialDesc(transport, source, features, roughness, metalness, ior, transmission,
                luminance, RtMaterialRegistry.BUILTIN_SURFACE_IMPLEMENTATION);
    }

    static RtMaterialDesc runtimeTextureDescription(int features, boolean neutral,
                                                     float defaultEmissionLuminance) {
        boolean authored = (features & (RtMaterialRegistry.FEATURE_SPEC | RtMaterialRegistry.FEATURE_NORMAL)) != 0;
        RtMaterialDesc.Source source = neutral ? RtMaterialDesc.Source.NEUTRAL
                : authored ? RtMaterialDesc.Source.AUTHORED_TEXTURE : RtMaterialDesc.Source.DERIVED_TEXTURE;
        boolean emissionCapable = (features & RtMaterialRegistry.FEATURE_EMISSION_MASK) != 0;
        return new RtMaterialDesc(RtMaterialRegistry.TRANSPORT_SURFACE, source, features,
                (features & RtMaterialRegistry.FEATURE_SPEC) != 0 ? 1.0f
                        : OpenPbrMaterialDefaults.RUNTIME_TEXTURE_SPECULAR_ROUGHNESS,
                (features & RtMaterialRegistry.FEATURE_SPEC) != 0 ? 1.0f : 0.0f,
                OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR, 0,
                emissionCapable ? defaultEmissionLuminance : 0.0f,
                RtMaterialRegistry.BUILTIN_SURFACE_IMPLEMENTATION);
    }
}
