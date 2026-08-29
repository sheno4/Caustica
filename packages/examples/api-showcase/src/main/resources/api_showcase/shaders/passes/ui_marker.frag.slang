import showcase_pass_types;

[[vk::push_constant]] ShowcaseUiPush pushData;
[[vk::binding(0, 0)]] RaytracingAccelerationStructure sceneAccelerationStructure;

[shader("fragment")]
float4 main() : SV_Target0 {
    RayDesc ray;
    ray.Origin = float3(0.0, 0.0, 0.0);
    ray.TMin = 0.001;
    ray.Direction = float3(0.0, 0.0, 1.0);
    ray.TMax = 1.0;
    RayQuery<RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> query;
    query.TraceRayInline(sceneAccelerationStructure, RAY_FLAG_NONE, 0xff, ray);
    while (query.Proceed()) {
    }
    float visibility = query.CommittedStatus() == COMMITTED_NOTHING ? 1.0 : 0.45;
    return float4(visibility, 0.35 * visibility, 0.08, 0.75);
}
