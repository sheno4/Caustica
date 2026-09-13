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
