// Shared decode for the radiance and shadow any-hit entry points. Each ray type has its own shader so
// neither pays the other's register allocation: any-hit is the most frequently invoked stage in the
// pipeline and its allocation bounds occupancy for everything else in it.
//
// Only the producer's geometry decode differs; the material half of both entry points reads one 16-byte
// MaterialBinding and branches on nothing else.
//
//  * terrain (per-section): InstanceID() indexes the section table; GeometryIndex() is the SBT class,
//    and PrimitiveIndex() restarts at zero per geometry, so triBase re-forms the section-local triangle.
//  * entity (ENTITY_BIT / PARTICLE_BIT): InstanceID()'s low bits index the entity geometry table, whose
//    UVs are indexed rather than per-corner.
//
// Radiance and shadow rays share the exact Payload ABI, as Vulkan requires for every stage reachable by
// a trace. Shadow traversal uses albedo.rgb as accumulated transmittance and hitT as the nearest recorded
// crossing; radiance paths do not touch either field.
module any_hit_common;

import world_common;
import math;
import bindings;

/** One hit's material binding, its interpolated albedo UV, and the per-primitive tint. */
public struct HitBinding {
    public MaterialBinding binding;
    public float2 uv;
    public float3 tint; // vanilla 8-bit convention, still sRGB-encoded
};

public float3 barycentrics(float2 attribs) {
    return float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
}

public HitBinding decodeHit(float3 bary, uint pid) {
    HitBinding hit;
    uint materialId;
    if ((InstanceID() & (ENTITY_BIT | PARTICLE_BIT)) != 0u) {
        EntityGeom g = ConstPtr<EntityGeom>(pc.entityTableAddr)[InstanceID() & IDX_MASK];
        uint tri = pid + g.triBase[GeometryIndex()];
        ConstPtr<uint> indices = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> uvs = ConstPtr<float2>(g.uvAddr);
        hit.uv = bary.x * uvs[indices[3u * tri + 0u]]
                + bary.y * uvs[indices[3u * tri + 1u]]
                + bary.z * uvs[indices[3u * tri + 2u]];
        Prim pr = ConstPtr<Prim>(g.primAddr)[tri];
        materialId = pr.materialId;
        hit.tint = pr.tint.rgb;
    } else {
        Section sec = ConstPtr<Section>(pc.tableAddr)[InstanceID()];
        uint tri = pid + sec.triBase[GeometryIndex()];
        ConstPtr<float2> uvs = ConstPtr<float2>(sec.uvAddr);
        hit.uv = bary.x * uvs[3u * tri + 0u] + bary.y * uvs[3u * tri + 1u] + bary.z * uvs[3u * tri + 2u];
        TerrainPrim pr = ConstPtr<TerrainPrim>(sec.primAddr)[tri];
        materialId = pr.materialId;
        hit.tint = pr.tint.rgb;
    }
    hit.binding = ConstPtr<MaterialBinding>(pc.materialTableAddr)[materialId];
    return hit;
}

/**
 * The albedo texel the coverage test reads. Both producers reach the atlas through the same bindless
 * array, so the slot named by the binding is the only thing that varies.
 */
public float4 sampleCoverageTexel(HitBinding hit) {
    return entityAlbedoTex[NonUniformResourceIndex(bindingAlbedoSlot(hit.binding))]
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
            ^ pid * 2654435761u ^ (InstanceID() & IDX_MASK) * 1013904223u ^ asuint(RayTCurrent());
    return rndf(seed);
}
