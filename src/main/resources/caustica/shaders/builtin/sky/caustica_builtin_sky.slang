module caustica_builtin_sky;

import caustica_sky;

/** Resource-free reference environment used when no host sky model is selected. */
public struct BuiltinSky : ISkyModel {
    public float3 evaluateEnvironment(EnvironmentQuery query) {
        float horizon = 1.0 - abs(clamp(query.direction.y, -1.0, 1.0));
        float upper = max(query.direction.y, 0.0);
        float3 ground = float3(0.015, 0.018, 0.022);
        float3 zenith = float3(0.12, 0.20, 0.38);
        float3 haze = float3(0.28, 0.32, 0.36);
        return lerp(ground, zenith, upper) + haze * horizon * horizon * 0.15;
    }
};
