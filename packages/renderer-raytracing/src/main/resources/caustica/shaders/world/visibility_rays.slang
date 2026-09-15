module visibility_rays;
import caustica_types;
import caustica_api;
import bindings;
import world_common;
import world_minimal;
import retained_lights;
import trace_transport;

// TLAS-relative origin and scene-unit distances; absorption is per scene unit and direction is normalized.
struct VisibilityRay {
    float4 originMin;
    float4 directionMax;
    float4 absorptionIor;
};

[shader("raygeneration")]
void main<TComposition : IComposition>() {
    uint index = DispatchRaysIndex().x + DispatchRaysIndex().y * DispatchRaysDimensions().x;
    VisibilityRay input = ((ConstPtr<VisibilityRay>)worldBindings.visibilityRaysAddress)[index];
    DevicePtr<float4> results = (DevicePtr<float4>)worldBindings.visibilityResultsAddress;
    if (input.directionMax.w <= input.originMin.w) {
        results[index] = float4(1.0);
        return;
    }
    RayDesc ray;
    ray.Origin = input.originMin.xyz;
    ray.TMin = input.originMin.w;
    ray.Direction = input.directionMax.xyz;
    ray.TMax = input.directionMax.w;
    WorldPush frame = ((ConstPtr<WorldPush>)worldBindings.worldPushAddress)[0];
    // Match the 4096-unit procedural period so rebasing preserves quantized coverage seeds.
    int3 position = int3(floor((ray.Origin + frame.proceduralDomainOffset) * 16.0)) & int3(65535);
    uint seed = neeAtHash(asuint(position.x) ^ (asuint(position.y) * 0x9e3779b9u)
            ^ (asuint(position.z) * 0x85ebca6bu)) | 1u;
    float3 visibility = traceVisibilityRay<TComposition>(ray, input.absorptionIor.xyz,
            input.absorptionIor.w, seed);
    results[index] = float4(visibility, 1.0);
}

struct VolumeLightingPoint {
    float4 positionMin;
    float4 directionAnisotropy;
    float4 sampling;
    float4 ambientRadiance;
};

[shader("raygeneration")]
void volumeLighting<TComposition : IComposition>() {
    uint index = DispatchRaysIndex().x + DispatchRaysIndex().y * DispatchRaysDimensions().x;
    VolumeLightingPoint input = ((ConstPtr<VolumeLightingPoint>)worldBindings.visibilityRaysAddress)[index];
    DevicePtr<float4> results = (DevicePtr<float4>)worldBindings.visibilityResultsAddress;
    results[index] = float4(0, 0, 0, 1);
    if (input.sampling.w == 0.0) return;
    NeeAtState state = neeAtState(worldBindings.neeAtStateAddress)[0];
    uint2 pixel = min(uint2(input.sampling.xy * float2(state.extentWidth, state.extentHeight)),
            uint2(state.extentWidth - 1u, state.extentHeight - 1u));
    uint seed = asuint(input.sampling.z) | 1u;
    float3 lighting = traceVolumeLighting<TComposition>(input.positionMin.xyz,
            input.directionAnisotropy.xyz, input.directionAnisotropy.w, input.ambientRadiance.rgb,
            pixel, 8u, true, input.positionMin.w, TRACE_MAX_DISTANCE, seed);
    results[index] = float4(lighting, 1.0);
}
