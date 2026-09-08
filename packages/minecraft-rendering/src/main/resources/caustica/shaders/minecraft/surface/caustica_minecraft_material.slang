module caustica_minecraft_material;

import caustica_color;
import caustica_resources;
import caustica_types;

public static const uint MINECRAFT_TEXTURE_PRESENT = 1u;
public static const uint MINECRAFT_TEXTURE_LINEAR = 2u;
public static const uint MINECRAFT_DAMAGE_PRESENT = 4u;
public static const uint MINECRAFT_MATERIAL_SPEC = 1u;
public static const uint MINECRAFT_MATERIAL_NORMAL = 2u;
public static const uint MINECRAFT_MATERIAL_EMISSION_MASK = 4u;
public static const uint MINECRAFT_MATERIAL_SUBSURFACE_COLOR_BASE = 8u;
public static const uint MINECRAFT_MATERIAL_EMISSION_COLOR_BASE = 16u;
public static const uint MINECRAFT_MATERIAL_UNIFORM_EMISSION = 32u;

public struct MinecraftImplementationData {
    public uint64_t materialsAddress;
    public SamplerIndex samplerIndex;
    public uint reserved;
};

public struct MinecraftMaterialRecord {
    public uint features;
    public float maxLod;
    public SampledTexture2DIndex surface0Texture;
    public SampledTexture2DIndex surface1Texture;
    public SampledTexture2DIndex normalTexture;
    public SampledTexture2DIndex emissionTexture;
    public float4 materialUv;
    public float4 baseColorUv;
    public float3 baseColor;
    public float baseMetalness;
    public float specularRoughness;
    public float specularIor;
    public float transmissionWeight;
    public float subsurfaceWeight;
    public float3 emissionColor;
    public float emissionLuminance;
};

public struct MinecraftPrimitiveData {
    public float2 textureCoordinates[3];
    public float4 vertexColors[3];
    public float3 tint;
    public uint materialIndex;
    public SampledTexture2DIndex baseTexture;
    public SamplerIndex baseSampler;
    public uint textureFlags;
    public float primitiveEmission;
    public float3 tangent;
    public float3 bitangent;
};

public struct MinecraftInstanceData {
    public float3 tint;
    public uint flags;
    public SampledTexture2DIndex damageTexture;
    public float damageStrength;
};

public struct MinecraftHitData {
    public MinecraftImplementationData implementation;
    public MinecraftPrimitiveData primitive;
    public MinecraftMaterialRecord material;
    public MinecraftInstanceData instance;
    public float2 textureCoordinate;
    public float4 vertexColor;
};

float minecraftIor(float encoded) {
    float amplitude = clamp(encoded, 0.0, 254.0 / 255.0);
    return (1.0 + amplitude) / (1.0 - amplitude);
}

public MinecraftInstanceData minecraftInstanceData(uint64_t address) {
    if (address != 0u) return ShaderDataPtr<MinecraftInstanceData>(address)[0];
    MinecraftInstanceData instance = {};
    instance.tint = float3(1.0);
    return instance;
}

public MinecraftHitData minecraftHitData(uint64_t implementationData, uint64_t bindingData,
        uint64_t instanceData, uint primitiveIndex, float2 barycentrics) {
    MinecraftHitData hit = {};
    hit.implementation = ShaderDataPtr<MinecraftImplementationData>(implementationData)[0];
    hit.primitive = ShaderDataPtr<MinecraftPrimitiveData>(bindingData)[primitiveIndex];
    hit.material = ShaderDataPtr<MinecraftMaterialRecord>(hit.implementation.materialsAddress)
            [hit.primitive.materialIndex];
    hit.instance = minecraftInstanceData(instanceData);
    float3 weights = float3(1.0 - barycentrics.x - barycentrics.y,
            barycentrics.x, barycentrics.y);
    hit.textureCoordinate = hit.primitive.textureCoordinates[0] * weights.x
            + hit.primitive.textureCoordinates[1] * weights.y
            + hit.primitive.textureCoordinates[2] * weights.z;
    hit.vertexColor = hit.primitive.vertexColors[0] * weights.x
            + hit.primitive.vertexColors[1] * weights.y
            + hit.primitive.vertexColors[2] * weights.z;
    return hit;
}

public float4 minecraftSampleMaterial(MinecraftHitData hit, SampledTexture2DIndex texture,
        float2 coordinates, float lod) {
    return sampleTexture2DLod(texture, hit.implementation.samplerIndex, coordinates, lod);
}

public float4 minecraftSampleBase(MinecraftHitData hit, float2 coordinates, float lod) {
    return sampleTexture2DLod(hit.primitive.baseTexture, hit.primitive.baseSampler, coordinates, lod);
}

public OpenPbrSurface evaluateMinecraftMaterial(SurfaceInput input) {
    MinecraftHitData hit = minecraftHitData(input.implementationData, input.bindingData,
            input.instanceData, input.primitiveIndex, input.barycentrics);
    MinecraftMaterialRecord data = hit.material;
    OpenPbrSurface material = {};
    material.base_color = data.baseColor;
    material.base_metalness = data.baseMetalness;
    material.specular_roughness = data.specularRoughness;
    material.specular_ior = data.specularIor;
    material.transmission_weight = data.transmissionWeight;
    material.subsurface_weight = data.subsurfaceWeight;
    material.emission_color = data.emissionColor;
    material.emission_luminance = data.emissionLuminance;

    float3 geometryTint = hit.primitive.tint * hit.instance.tint * hit.vertexColor.rgb;
    if ((hit.primitive.textureFlags & MINECRAFT_TEXTURE_PRESENT) == 0u) {
        material.base_color *= geometryTint;
    } else {
        float4 texel = minecraftSampleBase(hit, hit.textureCoordinate, 0.0);
        float3 sampled = (hit.primitive.textureFlags & MINECRAFT_TEXTURE_LINEAR) != 0u
                ? texel.rgb : srgbToLinear(texel.rgb);
        sampled = bt709ToAcesCg(sampled);
        material.base_color *= sampled * geometryTint;
    }
    float3 emissionBaseColor = material.base_color;

    material.emission_luminance *= (data.features & MINECRAFT_MATERIAL_UNIFORM_EMISSION) != 0u
            ? 1.0 : hit.primitive.primitiveEmission;
    float2 localUv = clamp((hit.textureCoordinate - data.baseColorUv.xy) * data.baseColorUv.zw,
            float2(0.0), float2(1.0));
    float2 pageUv = data.materialUv.xy + localUv * data.materialUv.zw;
    float pageLod = min(input.textureFootprint, data.maxLod);
    if ((data.features & (MINECRAFT_MATERIAL_SPEC | MINECRAFT_MATERIAL_EMISSION_MASK)) != 0u) {
        float4 surface0 = minecraftSampleMaterial(hit, data.surface0Texture, pageUv, pageLod);
        if ((data.features & MINECRAFT_MATERIAL_SPEC) != 0u) {
            float4 surface1 = minecraftSampleMaterial(hit, data.surface1Texture, pageUv, pageLod);
            material.specular_roughness *= surface0.r;
            material.base_metalness *= surface0.g;
            material.subsurface_weight *= surface0.a;
            if (material.transmission_weight == 0.0) material.specular_ior = minecraftIor(surface1.a);
            float3 authored = clamp(bt709ToAcesCg(surface1.rgb), float3(0.0), float3(1.0));
            material.base_color = lerp(material.base_color, authored * data.baseColor,
                    material.base_metalness);
        }
        if ((data.features & MINECRAFT_MATERIAL_EMISSION_MASK) != 0u) {
            material.emission_luminance *= surface0.b;
            material.emission_color *= bt709ToAcesCg(
                    minecraftSampleMaterial(hit, data.emissionTexture, pageUv, pageLod).rgb);
        }
    }
    // Base-bound emission follows the live atlas animation and geometry tint.
    if ((data.features & MINECRAFT_MATERIAL_EMISSION_COLOR_BASE) != 0u) {
        material.emission_color *= emissionBaseColor;
    }
    if ((data.features & MINECRAFT_MATERIAL_SUBSURFACE_COLOR_BASE) != 0u) {
        material.subsurface_color = material.base_color;
    }
    if ((data.features & MINECRAFT_MATERIAL_NORMAL) != 0u) {
        float2 encoded = minecraftSampleMaterial(hit, data.normalTexture, pageUv, pageLod).xy * 2.0 - 1.0;
        float z = sqrt(max(0.0, 1.0 - dot(encoded, encoded)));
        float3 objectNormal = normalize(hit.primitive.tangent * encoded.x
                + hit.primitive.bitangent * encoded.y
                + cross(hit.primitive.tangent, hit.primitive.bitangent) * z);
        material.geometry_normal = normalize(transformNormal(input.normalToScene, objectNormal));
        if ((input.evaluationFlags & SURFACE_RECONSTRUCTION_ANCHOR) != 0u) {
            material.previous_geometry_normal = normalize(
                    transformNormal(input.previousNormalToScene, objectNormal));
        }
    }
    if ((hit.instance.flags & MINECRAFT_DAMAGE_PRESENT) != 0u) {
        float damage = minecraftSampleMaterial(hit, hit.instance.damageTexture,
                hit.textureCoordinate, 0.0).a * clamp(hit.instance.damageStrength, 0.0, 1.0);
        material.base_color = lerp(material.base_color, material.base_color * 0.18, damage);
        material.specular_roughness = lerp(material.specular_roughness, 0.92, damage);
    }
    return material;
}
