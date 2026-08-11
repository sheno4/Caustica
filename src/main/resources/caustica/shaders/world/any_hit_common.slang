// Shared decode for the radiance and shadow any-hit entry points. Each ray type has its own shader so
// neither pays the other's register allocation: any-hit is the most frequently invoked stage in the
// pipeline and its allocation bounds occupancy for everything else in it.
//
// GeometryIndex() is the SBT class and PrimitiveIndex() restarts at zero per geometry, so triangleBase
// re-forms the record-local triangle. UV storage may be indexed or already triangle-corner ordered.
//
// Radiance and shadow rays share the exact Payload ABI, as Vulkan requires for every stage reachable by
// a trace. Shadow traversal uses albedo.rgb as accumulated transmittance and hitT as the nearest recorded
// crossing; radiance paths do not touch either field.
module any_hit_common;

import world_common;
import math;
import bindings;
import caustica_texture_resources;

/** One hit's material binding, its interpolated base-color UV, and the per-primitive tint. */
public struct HitBinding {
    public MaterialBinding binding;
    public float2 uv;
    public float3 tint; // source 8-bit convention, still sRGB-encoded
};

public float3 barycentrics(float2 attribs) {
    return float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
}

public HitBinding decodeHit(float3 bary, uint pid) {
    HitBinding hit;
    GeometryRecord g = ConstPtr<GeometryRecord>(pc.geometryTableAddr)[InstanceID()];
    uint tri = pid + g.triangleBase[GeometryIndex()];
    ConstPtr<float2> textureCoordinates = ConstPtr<float2>(g.textureCoordinateAddress);
    if ((g.flags & GEOMETRY_INDEXED_TEXTURE_COORDINATES) != 0u) {
        ConstPtr<uint> indices = ConstPtr<uint>(g.indexAddress);
        hit.uv = bary.x * textureCoordinates[indices[3u * tri + 0u]]
                + bary.y * textureCoordinates[indices[3u * tri + 1u]]
                + bary.z * textureCoordinates[indices[3u * tri + 2u]];
    } else {
        hit.uv = bary.x * textureCoordinates[3u * tri + 0u]
                + bary.y * textureCoordinates[3u * tri + 1u]
                + bary.z * textureCoordinates[3u * tri + 2u];
    }
    Prim pr = ConstPtr<Prim>(g.primitiveAddress)[tri];
    hit.tint = pr.tint.rgb;
    hit.binding = ConstPtr<MaterialBinding>(pc.materialTableAddr)[pr.materialId];
    return hit;
}

/**
 * The base-color texel the coverage test reads. Every geometry source reaches its texture through the same
 * bindless array, so the texture index named by the material binding is the only thing that varies.
 */
public float4 sampleCoverageTexel(HitBinding hit) {
    return baseColorTextures[NonUniformResourceIndex(bindingBaseColorTextureIndex(hit.binding))]
            .SampleLevel(hit.uv, 0.0);
}

/**
 * White-noise alpha threshold in [0,1). DLSS Ray Reconstruction's integration guide asks for white-noise
 * stochastic input rather than a spatial pattern, so this hashes pixel, frame, primitive and hit distance
 * instead of rotating an ordered matrix. Hashing also keeps the shader free of a dynamically indexed
 * constant array, which any-hit cannot afford to spill.
 */
public float stochasticAlphaThreshold(uint pid) {
    uint2 px = DispatchRaysIndex().xy;
    uint seed = px.x * 73856093u ^ px.y * 19349663u ^ pc.frameIndex * 83492791u
            ^ pid * 2654435761u ^ InstanceID() * 1013904223u ^ asuint(RayTCurrent());
    return rndf(seed);
}
