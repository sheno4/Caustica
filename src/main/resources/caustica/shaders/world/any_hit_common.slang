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
import caustica_coverage;

/** The engine-owned traversal binding and the narrow input exposed to coverage implementations. */
public struct HitBinding {
    public MaterialBinding binding;
    public CoverageInput coverageInput;
};

public float3 barycentrics(float2 attribs) {
    return float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
}

public HitBinding decodeHit(float3 bary, uint pid) {
    HitBinding hit;
    GeometryRecord g = ConstPtr<GeometryRecord>(pc.geometryTableAddr)[InstanceID()];
    uint tri = pid + g.triangleBase[GeometryIndex()];
    ConstPtr<float2> textureCoordinates = ConstPtr<float2>(g.textureCoordinateAddress);
    ConstPtr<uint> indices = ConstPtr<uint>(g.indexAddress);
    uint i0 = indices[3u * tri + 0u];
    uint i1 = indices[3u * tri + 1u];
    uint i2 = indices[3u * tri + 2u];
    Prim pr = ConstPtr<Prim>(g.primitiveAddress)[tri];
    hit.coverageInput.materialId = pr.materialId;
    if ((g.flags & GEOMETRY_INDEXED_TEXTURE_COORDINATES) != 0u) {
        hit.coverageInput.textureCoordinate = bary.x * textureCoordinates[i0]
                + bary.y * textureCoordinates[i1]
                + bary.z * textureCoordinates[i2];
    } else {
        hit.coverageInput.textureCoordinate = bary.x * textureCoordinates[3u * tri + 0u]
                + bary.y * textureCoordinates[3u * tri + 1u]
                + bary.z * textureCoordinates[3u * tri + 2u];
    }
    hit.coverageInput.tint = pr.tint.rgb;
    hit.coverageInput.vertexColor = float4(1.0);
    if ((g.flags & GEOMETRY_VERTEX_COLORS) != 0u) {
        ConstPtr<float> colors = ConstPtr<float>(g.vertexColorAddress);
        uint c0 = 4u * i0;
        uint c1 = 4u * i1;
        uint c2 = 4u * i2;
        float4 color0 = float4(colors[c0], colors[c0 + 1u], colors[c0 + 2u], colors[c0 + 3u]);
        float4 color1 = float4(colors[c1], colors[c1 + 1u], colors[c1 + 2u], colors[c1 + 3u]);
        float4 color2 = float4(colors[c2], colors[c2 + 1u], colors[c2 + 2u], colors[c2 + 3u]);
        float4 color = bary.x * color0 + bary.y * color1 + bary.z * color2;
        hit.coverageInput.vertexColor = float4(color.rgb, clamp(color.a, 0.0, 1.0));
    }
    hit.binding = ConstPtr<MaterialBinding>(pc.materialTableAddr)[pr.materialId];
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
            ^ pid * 2654435761u ^ InstanceID() * 1013904223u ^ asuint(RayTCurrent());
    return rndf(seed);
}
