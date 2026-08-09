// Shared decode for the radiance and shadow any-hit entry points. Each ray type has its own shader so
// neither pays the other's register allocation: any-hit is the most frequently invoked stage in the
// pipeline and its allocation bounds occupancy for everything else in it.
//
//  * terrain (per-section): InstanceID() indexes the section table; GeometryIndex() is the material
//    bucket, and PrimitiveIndex() restarts at zero per geometry, so triBase re-forms the section-local
//    triangle.
//  * entity (ENTITY_BIT / PARTICLE_BIT): InstanceID()'s low bits index the entity geometry table, whose
//    UVs are indexed rather than per-corner.
//
// Radiance and shadow rays share the exact Payload ABI, as Vulkan requires for every stage reachable by
// a trace. Shadow traversal uses albedo.rgb as accumulated transmittance and hitT as the nearest-water
// crossing; radiance paths do not touch either field.
module any_hit_common;

import world_common;
import math;
import bindings;

public static const float ALPHA_CUTOFF = 0.5;        // terrain (matches the cutout block models)
public static const float ENTITY_ALPHA_CUTOFF = 0.1; // entities: only discard near-fully-transparent texels
// Faint baseline extinction added to every translucent hit regardless of its color, so perfectly clear
// glass/ice still dims a shadow ray a little instead of being fully invisible to it (real glass isn't a
// perfect transmitter either). Additive with the color-derived extinction, not a floor/clamp — a tinted
// pane still absorbs its own color on top of this.
public static const float TRANSLUCENT_NEUTRAL_EXTINCTION = 0.15;
public static const float WATER_SHADOW_TINT = 0.5;

/** One entity/particle triangle's interpolated UV, primitive record and material. */
public struct EntityHit {
    public Prim prim;
    public MaterialHeader material;
    public float4 texel;
};

public EntityHit decodeEntityHit(float3 bary, uint pid) {
    EntityGeom g = ConstPtr<EntityGeom>(pc.entityTableAddr)[InstanceID() & IDX_MASK];
    uint tri = pid + g.triBase[GeometryIndex()];
    ConstPtr<uint> indices = ConstPtr<uint>(g.idxAddr);
    ConstPtr<float2> uvs = ConstPtr<float2>(g.uvAddr);
    float2 uv = bary.x * uvs[indices[3u * tri + 0u]]
            + bary.y * uvs[indices[3u * tri + 1u]]
            + bary.z * uvs[indices[3u * tri + 2u]];
    EntityHit hit;
    hit.prim = ConstPtr<Prim>(g.primAddr)[tri];
    hit.material = ConstPtr<MaterialHeader>(pc.materialTableAddr)[hit.prim.materialId];
    hit.texel = entityAlbedoTex[NonUniformResourceIndex(hit.material.albedoSlot)].SampleLevel(uv, 0.0);
    return hit;
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

/** Whether this entity hit's texel fails its alpha test, using stochastic coverage when the material asks. */
public bool entityAlphaFails(EntityHit hit, uint pid, bool allowStochastic) {
    if (allowStochastic && (hit.material.features & MATERIAL_FEATURE_STOCHASTIC_ALPHA) != 0u) {
        return stochasticAlphaThreshold(pid) >= clamp(hit.texel.a, 0.0, 1.0);
    }
    return hit.texel.a < ENTITY_ALPHA_CUTOFF;
}

/** Terrain cutout alpha, sampled from the material's albedo slot through the shared bindless array. */
public bool terrainCutoutFails(Section sec, float3 bary, uint tri) {
    ConstPtr<float2> uvs = ConstPtr<float2>(sec.uvAddr);
    float2 uv = bary.x * uvs[3u * tri + 0u] + bary.y * uvs[3u * tri + 1u] + bary.z * uvs[3u * tri + 2u];
    uint slot = ConstPtr<MaterialHeader>(pc.materialTableAddr)[
            ConstPtr<TerrainPrim>(sec.primAddr)[tri].materialId].albedoSlot;
    return entityAlbedoTex[NonUniformResourceIndex(slot)].SampleLevel(uv, 0.0).a < ALPHA_CUTOFF;
}

public float3 barycentrics(float2 attribs) {
    return float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
}
