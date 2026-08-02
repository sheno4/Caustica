module caustica_default_environment;

import caustica_ray_pack_api;

static const float PI = 3.14159265359;
static const float SUN_SHADOW_RADIUS = 0.0104719755;
static const float MOON_SHADOW_RADIUS = 0.0261799388;
static const float SUN_DISC_RADIUS = 0.291469985;
static const float MOON_DISC_RADIUS = 0.19739626;
static const float SUN_ILLUMINANCE = 128000.0;
static const float MOON_ILLUMINANCE = 5.0;

float defaultDiscRadiance(float illuminance, float radius) {
    return illuminance / (PI * sin(radius) * sin(radius));
}

public float3 defaultEvaluateEnvironment(PackEnvironmentQuery query) {
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

public uint defaultDirectionalLightCount(PackFrameContext frame) {
    return (frame.dimensionFlags & PACK_DIMENSION_HAS_SKY) != 0u ? 2u : 0u;
}

public PackDirectionalLight defaultDirectionalLight(PackFrameContext frame, uint index) {
    PackDirectionalLight light;
    if (index == 0u) {
        light.direction = normalize(frame.sunDirection);
        light.irradiance = float3(SUN_ILLUMINANCE) * (1.0 - frame.nightFraction);
        light.angularRadius = SUN_SHADOW_RADIUS;
    } else {
        light.direction = normalize(frame.moonDirection);
        light.irradiance = float3(MOON_ILLUMINANCE) * frame.nightFraction;
        light.angularRadius = MOON_SHADOW_RADIUS;
    }
    return light;
}
