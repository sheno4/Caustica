import world_common;
import bindings;

public static const float ENTITY_ALPHA_CUTOFF = 0.1;

public float2 materialPageUv(MaterialHeader header, float2 atlasUv) {
    float2 localUv = saturate((atlasUv - header.albedoUv.xy) * header.albedoUv.zw);
    return header.materialUv.xy + localUv * header.materialUv.zw;
}

public float materialPageLod(MaterialHeader header, float lod) {
    float maximumLod = float((header.features >> MATERIAL_MAX_LOD_SHIFT) & MATERIAL_MAX_LOD_MASK);
    return min(lod, maximumLod);
}

public float4 sampleMaterialSurface0(uint page, float2 pageUv, float lod) {
    return materialSurface0Tex[NonUniformResourceIndex(page)].SampleLevel(pageUv, lod);
}

public float4 sampleMaterialNormalAo(uint page, float2 pageUv, float lod) {
    return materialNormalAoTex[NonUniformResourceIndex(page)].SampleLevel(pageUv, lod);
}

public float materialNormalAo(float aoBlue) {
    return lerp(1.0, aoBlue, 0.5);
}

public float materialEmission(MaterialHeader header, float textureEmission, float fallbackEmission) {
    // Authored LabPBR emission is absolute within the material. Heuristic masks
    // use the per-primitive block-state emission as their scale.
    float emission = fallbackEmission;
    if ((header.features & MATERIAL_FEATURE_SPEC) != 0u) {
        emission = textureEmission;
    } else if ((header.features & MATERIAL_FEATURE_HEURISTIC_EMISSION) != 0u) {
        emission *= textureEmission;
    }
    float strength = float((header.features >> MATERIAL_EMISSION_STRENGTH_SHIFT)
            & MATERIAL_EMISSION_STRENGTH_MASK)
            * (MATERIAL_MAX_EMISSION_STRENGTH / float(MATERIAL_EMISSION_STRENGTH_MASK));
    return emission * strength;
}
