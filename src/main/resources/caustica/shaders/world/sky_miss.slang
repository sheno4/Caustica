module sky_miss;

import world_minimal;
import caustica_api;
import caustica_environment;

public void runEnvironmentMiss<TComposition : IComposition>(inout WorldPayload payload) {
    TComposition.Environments environments = {};
    payload.radiance = max(environments.evaluateEnvironment(0u, worldEnvironmentQuery()), float3(0.0));
    payload.hitDistance = -1.0;
}

[shader("miss")]
void main<TComposition : IComposition>(inout WorldPayload payload) {
    runEnvironmentMiss<TComposition>(payload);
}
