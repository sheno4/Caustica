package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialDefaults;
import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialProfile;

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
                                             RtMaterialDesc.EmissionSummary emissionSummary,
                                             float dielectricIor, float defaultEmissionLuminance) {
        boolean medium = transport == RtMaterialRegistry.TRANSPORT_MEDIUM_BOUNDARY;
        float roughness = medium ? OpenPbrMaterialDefaults.TRANSMISSIVE_SPECULAR_ROUGHNESS
                : profile.specularRoughness();
        float metalness = medium ? 0 : profile.baseMetalness();
        float ior = medium ? dielectricIor : OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR;
        float transmission = medium ? 1 : 0;
        boolean authored = (features & (RtMaterialRegistry.FEATURE_SPEC | RtMaterialRegistry.FEATURE_NORMAL)) != 0;
        RtMaterialDesc.Source source = neutral ? RtMaterialDesc.Source.NEUTRAL
                : authored ? RtMaterialDesc.Source.AUTHORED_TEXTURE : RtMaterialDesc.Source.DERIVED_TEXTURE;
        RtMaterialDesc.EmissionSource emissionSource;
        if ((features & RtMaterialRegistry.FEATURE_SPEC) != 0) emissionSource = RtMaterialDesc.EmissionSource.AUTHORED_MASK;
        else if ((features & RtMaterialRegistry.FEATURE_EMISSION_MASK) != 0) emissionSource = RtMaterialDesc.EmissionSource.DERIVED_MASK;
        else if (emitting) emissionSource = RtMaterialDesc.EmissionSource.GEOMETRY_UNIFORM;
        else emissionSource = RtMaterialDesc.EmissionSource.NONE;
        float luminance = emissionSource == RtMaterialDesc.EmissionSource.NONE ? 0 : defaultEmissionLuminance;
        return new RtMaterialDesc(transport, source, features, roughness, metalness, ior, transmission,
                emissionSource, luminance, emissionSummary, RtMaterialRegistry.BUILTIN_SURFACE_IMPLEMENTATION);
    }

    static RtMaterialDesc runtimeTextureDescription(int features, boolean neutral,
                                                     RtMaterialDesc.EmissionSummary emissionSummary,
                                                     float defaultEmissionLuminance) {
        boolean authored = (features & (RtMaterialRegistry.FEATURE_SPEC | RtMaterialRegistry.FEATURE_NORMAL)) != 0;
        RtMaterialDesc.Source source = neutral ? RtMaterialDesc.Source.NEUTRAL
                : authored ? RtMaterialDesc.Source.AUTHORED_TEXTURE : RtMaterialDesc.Source.DERIVED_TEXTURE;
        RtMaterialDesc.EmissionSource emissionSource = (features & RtMaterialRegistry.FEATURE_SPEC) != 0
                ? RtMaterialDesc.EmissionSource.AUTHORED_MASK : RtMaterialDesc.EmissionSource.NONE;
        return new RtMaterialDesc(RtMaterialRegistry.TRANSPORT_SURFACE, source, features,
                OpenPbrMaterialDefaults.RUNTIME_TEXTURE_SPECULAR_ROUGHNESS, 0,
                OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR, 0, emissionSource,
                emissionSource == RtMaterialDesc.EmissionSource.NONE ? 0 : defaultEmissionLuminance,
                emissionSummary, RtMaterialRegistry.BUILTIN_SURFACE_IMPLEMENTATION);
    }
}
