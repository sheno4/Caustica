module caustica_minecraft_material;

import caustica_color;
import caustica_texture_resources;
import caustica_types;

static const uint MATERIAL_FEATURE_SPEC = 1u;
static const uint MATERIAL_FEATURE_NORMAL = 2u;
static const uint MATERIAL_FEATURE_EMISSION_MASK = 4u;
static const uint MATERIAL_FEATURE_SUBSURFACE_COLOR_BASE = 8u;
static const uint MATERIAL_FEATURE_UNIFORM_EMISSION = 32u;

struct MinecraftMaterialData {
    uint features;
    float maxLod;
    uint surface0Texture;
    uint surface1Texture;
    uint normalTexture;
    uint emissionTexture;
    float4 materialUv;
    float4 baseColorUv;
};

MinecraftMaterialData minecraftMaterialData(MaterialProviderData source) {
    MinecraftMaterialData data;
    data.features = source.words0.x & 0x00FFFFFFu;
    data.maxLod = float(source.words0.x >> 24u);
    data.surface0Texture = source.words0.y & 0xFFFFu;
    data.surface1Texture = source.words0.y >> 16u;
    data.normalTexture = source.words0.z & 0xFFFFu;
    data.emissionTexture = source.words0.z >> 16u;
    data.materialUv = asfloat(uint4(source.words0.w, source.words1.x,
            source.words1.y, source.words1.z));
    data.baseColorUv = asfloat(uint4(source.words1.w, source.words2.x,
            source.words2.y, source.words2.z));
    return data;
}

float minecraftIor(float encoded) {
    float amplitude = clamp(encoded, 0.0, 254.0 / 255.0);
    return (1.0 + amplitude) / (1.0 - amplitude);
}

float3 minecraftNormal(SurfaceInput input, float3 baseNormal, float2 encoded) {
    if (dot(input.tangent, input.tangent) <= 1.0e-12) {
        return baseNormal;
    }
    float2 xy = encoded * 2.0 - 1.0;
    float z = sqrt(max(0.0, 1.0 - dot(xy, xy)));
    float3 normal = normalize(xy.x * input.tangent + xy.y * input.bitangent + z * baseNormal);
    float normalToView = dot(normal, input.outgoingDirection);
    return normalToView < 0.02
            ? normalize(normal + input.outgoingDirection * (0.02 - normalToView)) : normal;
}

/** Decode Minecraft's provider textures into the engine-initialized OpenPBR description. */
public void evaluateMinecraftMaterial(SurfaceInput input, inout MaterialInput material) {
    MinecraftMaterialData data = minecraftMaterialData(input.providerData);
    float3 baseColorFactor = material.baseColor;
    float3 geometryTint = input.tint * input.vertexColor.rgb;
    if ((input.baseTextureFlags & SURFACE_BASE_TEXTURE_PRESENT) == 0u) {
        material.baseColor *= geometryTint;
    } else {
        float4 texel = providerTextures[NonUniformResourceIndex(input.baseTextureIndex)]
                .SampleLevel(input.textureCoordinate, input.textureLod);
        float3 sampled = (input.baseTextureFlags & SURFACE_BASE_TEXTURE_LINEAR) != 0u
                ? texel.rgb : srgbToLinear(texel.rgb);
        sampled = bt709ToAcesCg(sampled);
        float3 resolved = material.transmissionWeight > 0.0
                ? lerp(float3(1.0), sampled * geometryTint, texel.a * input.vertexColor.a)
                : sampled * geometryTint;
        material.baseColor *= resolved;
    }

    material.emissionLuminance *= (data.features & MATERIAL_FEATURE_UNIFORM_EMISSION) != 0u
            ? 1.0 : input.primitiveEmission;
    float2 localUv = clamp((input.textureCoordinate - data.baseColorUv.xy) * data.baseColorUv.zw,
            float2(0.0), float2(1.0));
    float2 pageUv = data.materialUv.xy + localUv * data.materialUv.zw;
    float pageLod = min(input.textureLod, data.maxLod);
    if ((data.features & (MATERIAL_FEATURE_SPEC | MATERIAL_FEATURE_EMISSION_MASK)) != 0u) {
        float4 surface0 = providerTextures[NonUniformResourceIndex(data.surface0Texture)]
                .SampleLevel(pageUv, pageLod);
        if ((data.features & MATERIAL_FEATURE_SPEC) != 0u) {
            float4 surface1 = providerTextures[NonUniformResourceIndex(data.surface1Texture)]
                    .SampleLevel(pageUv, pageLod);
            material.specularRoughness *= surface0.r;
            material.baseMetalness *= surface0.g;
            material.subsurfaceWeight *= surface0.a;
            if (material.transmissionWeight == 0.0) {
                material.specularIor = minecraftIor(surface1.a);
            }
            float3 authored = clamp(bt709ToAcesCg(surface1.rgb), float3(0.0), float3(1.0));
            material.baseColor = lerp(material.baseColor,
                    authored * baseColorFactor, material.baseMetalness);
        }
        if ((data.features & MATERIAL_FEATURE_EMISSION_MASK) != 0u) {
            material.emissionLuminance *= surface0.b;
            float3 emission = providerTextures[NonUniformResourceIndex(data.emissionTexture)]
                    .SampleLevel(pageUv, pageLod).rgb;
            material.emissionColor *= bt709ToAcesCg(emission);
        }
    }
    if ((data.features & MATERIAL_FEATURE_SUBSURFACE_COLOR_BASE) != 0u) {
        material.subsurfaceColor = material.baseColor;
    }
    if ((data.features & MATERIAL_FEATURE_NORMAL) != 0u) {
        float2 normal = providerTextures[NonUniformResourceIndex(data.normalTexture)]
                .SampleLevel(pageUv, pageLod).xy;
        material.geometryNormal = minecraftNormal(input, material.geometryNormal, normal);
        material.previousGeometryNormal = material.geometryNormal;
    }
}
