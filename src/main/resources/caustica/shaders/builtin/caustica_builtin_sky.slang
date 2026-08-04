module caustica_builtin_sky;

import caustica_types;
import caustica_sky;

static const float PI = 3.14159265359;
static const float SUN_DISC_RADIUS = 0.291469985;
static const float MOON_DISC_RADIUS = 0.19739626;
static const float SUN_ILLUMINANCE = 128000.0;
static const float MOON_ILLUMINANCE = 5.0;

float defaultDiscRadiance(float illuminance, float radius) {
    return illuminance / (PI * sin(radius) * sin(radius));
}

float3 builtinEvaluateEnvironment(EnvironmentQuery query) {
    float elevation = clamp(query.direction.y * 0.5 + 0.5, 0.0, 1.0);
    float3 dayHorizon = float3(0.22, 0.32, 0.55);
    float3 dayZenith = float3(0.04, 0.18, 0.65);
    float3 nightHorizon = float3(0.0008, 0.0011, 0.0020);
    float3 nightZenith = float3(0.0002, 0.00035, 0.0012);
    float weatherAttenuation = 1.0 - 0.75 * clamp(max(query.frame.rain, query.frame.thunder), 0.0, 1.0);
    float3 daySky = lerp(dayHorizon, dayZenith, sqrt(elevation)) * weatherAttenuation;
    float3 nightSky = lerp(nightHorizon, nightZenith, sqrt(elevation));
    float3 radiance = lerp(daySky, nightSky, query.frame.nightFraction);

    if (query.showCelestialBodies) {
        float sunDisc = dot(query.direction, query.frame.sunDirection) >= cos(SUN_DISC_RADIUS)
                ? 1.0 : 0.0;
        float moonDisc = dot(query.direction, query.frame.moonDirection) >= cos(MOON_DISC_RADIUS)
                ? 1.0 : 0.0;
        radiance += sunDisc * defaultDiscRadiance(SUN_ILLUMINANCE, SUN_DISC_RADIUS)
                * (1.0 - query.frame.nightFraction);
        radiance += moonDisc * defaultDiscRadiance(MOON_ILLUMINANCE, MOON_DISC_RADIUS)
                * query.frame.nightFraction;
    }
    return radiance;
}

public struct BuiltinSky : ISkyModel {
    public float3 evaluateEnvironment(EnvironmentQuery query) {
        return builtinEvaluateEnvironment(query);
    }
};
