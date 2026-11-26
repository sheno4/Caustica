import world_common;
import world_core;
import bindings;
import math;
import trace;
import lighting;
import material_emission;

float3 capturedPosition(ConstPtr<float> positions, uint index) {
    uint offset = 3u * index; // The BLAS uses an R32G32B32 buffer with a 12-byte stride.
    return float3(positions[offset], positions[offset + 1u], positions[offset + 2u]);
}

float3 viewModelEmission(MaterialHeader header, Prim primitive, float2 atlasUv,
                         float2 uvEdge1, float2 uvEdge2, bool anyHit) {
    int slot = int(primitive.tint.w + 0.5);
    float4 texel = entityAlbedoTex[NonUniformResourceIndex(slot)].SampleLevel(atlasUv, 0.0);
    // The source ray's coverage is evaluated in expectation at the sampled texel.
    bool stochastic = (header.features & MATERIAL_FEATURE_STOCHASTIC_ALPHA) != 0u;
    if (anyHit && !stochastic && texel.a < ENTITY_ALPHA_CUTOFF) return float3(0.0);
    float coverage = anyHit && stochastic ? saturate(texel.a) : 1.0;
    float2 pageUv = materialPageUv(header, atlasUv);
    uint page = header.texturePage;
    float mask = 0.0;
    if ((header.features & (MATERIAL_FEATURE_SPEC | MATERIAL_FEATURE_HEURISTIC_EMISSION)) != 0u) {
        mask = sampleMaterialSurface0(page, pageUv, 0.0).b;
    }
    float emission = materialEmission(header, mask, primitive.normal.w);
    if (emission <= 0.0) return float3(0.0);

    float ao = 1.0;
    float uvDeterminant = uvEdge1.x * uvEdge2.y - uvEdge1.y * uvEdge2.x;
    if ((header.features & MATERIAL_FEATURE_NORMAL) != 0u && abs(uvDeterminant) > 1.0e-12) {
        ao = materialNormalAo(sampleMaterialNormalAo(page, pageUv, 0.0).b);
    }
    float3 albedo = bt709ToAcesCg(srgbToLinear(texel.rgb) * srgbToLinear(primitive.tint.rgb));
    return albedo * (emission * coverage * ao);
}

// One uniform candidate and one uniform triangle-area sample. The inverse
// combined PDF is triangle area times the number of eligible packed primitives.
public float3 shadeViewModelLights(float3 hitPos, float3 n, float3 biasNormal, float3 v,
                                   float3 diffAlb, bool twoSided, float sss,
                                   uint shadowMask, inout uint seed) {
    uint count = worldPush.viewModelLightTriangles;
    if (count == 0u || luminance(diffAlb) <= 0.0) return float3(0.0);

    uint candidate = min(uint(rndf(seed) * float(count)), count - 1u);
    uint2 sampleInfo = ConstPtr<uint2>(worldPush.viewModelLightSamples)[candidate];
    uint pid = sampleInfo.x;
    EntityGeom geom = ConstPtr<EntityGeom>(worldPush.viewModelLightGeom)[0];
    Prim primitive = ConstPtr<Prim>(geom.primAddr)[pid];
    MaterialHeader header = ConstPtr<MaterialHeader>(pc.materialTableAddr)
            [primitive.materialId & ENTITY_PRIM_MATERIAL_MASK];
    ConstPtr<uint> index = ConstPtr<uint>(geom.idxAddr);
    uint i0 = index[3u * pid];
    uint i1 = index[3u * pid + 1u];
    uint i2 = index[3u * pid + 2u];
    ConstPtr<float> positions = ConstPtr<float>(worldPush.viewModelLightPositions);
    float3 p0 = capturedPosition(positions, i0);
    float3 e1 = capturedPosition(positions, i1) - p0;
    float3 e2 = capturedPosition(positions, i2) - p0;
    float3 geometricCross = cross(e1, e2);
    float twiceArea = length(geometricCross);
    if (twiceArea <= 1.0e-10) return float3(0.0);

    float root = sqrt(rndf(seed));
    float b2 = root * rndf(seed);
    float b1 = root - b2;
    // The transient viewmodel BLAS is translated from eye-relative space to
    // the current terrain rebase by the same camOffset used to start eye rays.
    float3 samplePosition = p0 + e1 * b1 + e2 * b2 + worldPush.camOffset;
    float3 direction = samplePosition - hitPos;
    bool front = dot(n, direction) > 0.0;
    if (!twoSided && (front ? dot(biasNormal, direction) <= 0.0 : sss <= 0.0)) return float3(0.0);

    ConstPtr<float2> uvs = ConstPtr<float2>(geom.uvAddr);
    float2 uv0 = uvs[i0];
    float2 uvEdge1 = uvs[i1] - uv0;
    float2 uvEdge2 = uvs[i2] - uv0;
    float2 atlasUv = uv0 + uvEdge1 * b1 + uvEdge2 * b2;
    float3 emission = viewModelEmission(header, primitive, atlasUv, uvEdge1, uvEdge2, pid >= geom.triBase[1]);
    if (luminance(emission) <= 0.0) return float3(0.0);

    // Closest hit emits on either side of captured entity triangles.
    float3 emitterNormal = geometricCross / twiceArea;
    if (dot(emitterNormal, direction) > 0.0) emitterNormal = -emitterNormal;
    float phat;
    // Specular continuations intersect the mesh directly. Zero alpha makes this
    // direct estimator's finite GGX term vanish, leaving the world diffuse/SSS terms.
    // Block items preserve their placed model's integrated power without changing surface luminance.
    float3 contribution = evalSampleContrib(samplePosition, emitterNormal, emission * asfloat(sampleInfo.y),
            (0.5 * twiceArea) * float(count), hitPos, n, v, -v, diffAlb, float3(0.0), 0.0, twoSided, sss, phat);
    if (phat <= 0.0) return float3(0.0);
    float3 origin = offsetSurfaceOrigin(hitPos, biasNormal, direction, SURF_BIAS);
    float3 shadowVector = samplePosition - origin;
    float distance = length(shadowVector);
    // Stop before the sampled triangle itself; the rest of the captured mesh
    // still participates in visibility.
    if (distance <= 2.0 * RAY_TMIN) return contribution;
    return contribution * visibility(shadowMask, origin, shadowVector / distance,
            distance - RAY_TMIN).transmittance;
}
