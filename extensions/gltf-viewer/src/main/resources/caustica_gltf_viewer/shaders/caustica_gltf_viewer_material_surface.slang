module caustica_gltf_viewer_material_surface;

import caustica_color;
import caustica_surface;
import caustica_texture_resources;
import caustica_types;

static const uint GLTF_METALLIC_ROUGHNESS_PRESENT = 1u;
static const uint GLTF_NORMAL_PRESENT = 2u;
static const uint GLTF_EMISSIVE_PRESENT = 4u;

struct GltfViewerMaterialData {
    uint flags;
    uint metallicRoughnessTexture;
    uint normalTexture;
    uint emissiveTexture;
    float normalScale;
};

GltfViewerMaterialData gltfViewerMaterialData(MaterialProviderData source) {
    GltfViewerMaterialData data;
    data.flags = source.words0.x;
    data.metallicRoughnessTexture = source.words0.y;
    data.normalTexture = source.words0.z;
    data.emissiveTexture = source.words0.w;
    data.normalScale = asfloat(source.words1.x);
    return data;
}

float3 gltfViewerNormal(SurfaceInput input, float3 baseNormal, float2 encoded, float scale) {
    if (dot(input.tangent, input.tangent) <= 1.0e-12) {
        return baseNormal;
    }
    float2 xy = (encoded * 2.0 - 1.0) * scale;
    float z = sqrt(max(0.0, 1.0 - dot(xy, xy)));
    float3 normal = normalize(xy.x * input.tangent + xy.y * input.bitangent + z * baseNormal);
    float normalToView = dot(normal, input.outgoingDirection);
    return normalToView < 0.02
            ? normalize(normal + input.outgoingDirection * (0.02 - normalToView)) : normal;
}

/** Resolves glTF 2.0 metallic-roughness textures into the engine's OpenPBR vocabulary. */
public struct GltfViewerMaterialSurface : ISurfaceModel {
    public override void evaluateSurface(SurfaceInput input, inout MaterialInput material) {
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

        GltfViewerMaterialData data = gltfViewerMaterialData(input.providerData);
        if ((data.flags & GLTF_METALLIC_ROUGHNESS_PRESENT) != 0u) {
            float4 texel = providerTextures[NonUniformResourceIndex(data.metallicRoughnessTexture)]
                    .SampleLevel(input.textureCoordinate, input.textureLod);
            material.specularRoughness *= texel.g;
            material.baseMetalness *= texel.b;
        }
        if ((data.flags & GLTF_EMISSIVE_PRESENT) != 0u) {
            float3 emission = providerTextures[NonUniformResourceIndex(data.emissiveTexture)]
                    .SampleLevel(input.textureCoordinate, input.textureLod).rgb;
            material.emissionColor *= bt709ToAcesCg(emission);
        }
        if ((data.flags & GLTF_NORMAL_PRESENT) != 0u) {
            float2 normal = providerTextures[NonUniformResourceIndex(data.normalTexture)]
                    .SampleLevel(input.textureCoordinate, input.textureLod).xy;
            material.geometryNormal = gltfViewerNormal(
                    input, material.geometryNormal, normal, data.normalScale);
            material.previousGeometryNormal = material.geometryNormal;
        }
    }
};
