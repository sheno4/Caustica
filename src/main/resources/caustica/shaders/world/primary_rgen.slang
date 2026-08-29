module primary_rgen;

import world_common;
import world_minimal;
import bindings;
import caustica_api;

public void runPrimary<TComposition : IComposition>() {
    uint2 pixel = DispatchRaysIndex().xy;
    uint2 extent = DispatchRaysDimensions().xy;
    ConstPtr<WorldPush> frame = (ConstPtr<WorldPush>)worldBindings.worldPushAddress;

    float2 uv = (float2(pixel) + 0.5) / float2(extent);
    float2 clip = uv * 2.0 - 1.0;
    float4 nearPoint = mul(frame[0].invViewProj, float4(clip, 1.0, 1.0));
    float4 farPoint = mul(frame[0].invViewProj, float4(clip, 0.0, 1.0));
    nearPoint.xyz /= nearPoint.w;
    farPoint.xyz /= farPoint.w;

    RayDesc ray;
    ray.Origin = nearPoint.xyz + frame[0].camOffset;
    ray.Direction = normalize(farPoint.xyz - nearPoint.xyz);
    ray.TMin = 0.001;
    ray.TMax = 10000.0;

    WorldPayload payload = {};
    payload.hitDistance = -1.0;
    TraceRay(worldAccelerationStructure(worldBindings), RAY_FLAG_NONE, 0xFFu,
            0u, 1u, 0u, ray, payload);
    worldFloat4StorageImage(worldBindings.resources.outputImage)[pixel] =
            float4(max(payload.radiance, float3(0.0)), 1.0);
}

[shader("raygeneration")]
void main<TComposition : IComposition>() {
    runPrimary<TComposition>();
}
