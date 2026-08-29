module showcase_environment;

import caustica_environment;
import caustica_types;

namespace api_showcase {
public struct OverworldSky : IEnvironmentModel {
    public float3 evaluateEnvironment(EnvironmentQuery query) {
        float horizon = saturate(query.direction.y * 0.5 + 0.5);
        return lerp(float3(0.02, 0.03, 0.05), float3(0.2, 0.45, 0.9), horizon);
    }
};

public struct NetherSky : IEnvironmentModel {
    public float3 evaluateEnvironment(EnvironmentQuery query) {
        float horizon = saturate(query.direction.y * 0.5 + 0.5);
        return lerp(float3(0.015, 0.002, 0.001), float3(0.18, 0.025, 0.01), horizon);
    }
};

public struct EndSky : IEnvironmentModel {
    public float3 evaluateEnvironment(EnvironmentQuery query) {
        float horizon = saturate(query.direction.y * 0.5 + 0.5);
        return lerp(float3(0.002, 0.001, 0.008), float3(0.08, 0.025, 0.14), horizon);
    }
};
}
