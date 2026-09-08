module caustica_minecraft_dimension_skies;

import caustica_environment;
import caustica_types;
import caustica_color;
import caustica_minecraft_celestial_dressing;

// Direction-space noise stays continuous across the dome and fixed as the camera moves.
float dimensionNoise(float3 p) {
    float3 cell = floor(p);
    float3 f = frac(p);
    f = f * f * (3.0 - 2.0 * f);
    return lerp(lerp(lerp(hash13(cell), hash13(cell + float3(1, 0, 0)), f.x),
                     lerp(hash13(cell + float3(0, 1, 0)), hash13(cell + float3(1, 1, 0)), f.x), f.y),
                lerp(lerp(hash13(cell + float3(0, 0, 1)), hash13(cell + float3(1, 0, 1)), f.x),
                     lerp(hash13(cell + float3(0, 1, 1)), hash13(cell + float3(1, 1, 1)), f.x), f.y), f.z);
}

float dimensionClouds(float3 p) {
    float value = 0.0;
    float weight = 0.5;
    for (int octave = 0; octave < 4; ++octave) {
        value += weight * dimensionNoise(p);
        p = p * 2.03 + float3(17.1, 9.2, 13.7);
        weight *= 0.5;
    }
    return value;
}

public struct MinecraftNetherSky : IEnvironmentModel {
    public float3 evaluateEnvironment(EnvironmentQuery query) {
        float3 dir = query.direction;
        float ash = dimensionClouds(dir * float3(3.0, 7.0, 3.0) + 11.0);
        float folds = dimensionClouds(dir * 9.0 + ash * 2.0);
        float horizon = exp(-5.0 * abs(dir.y + 0.12));
        float glow = horizon * smoothstep(0.22, 0.78, ash);
        float3 color = lerp(float3(0.032, 0.004, 0.009), float3(0.15, 0.025, 0.018), ash);
        color += float3(0.65, 0.12, 0.018) * glow * (0.4 + folds);
        color *= 1.0 - 0.55 * smoothstep(0.45, 0.75, folds);
        return bt709ToAcesCg(color);
    }
};

public struct MinecraftEndSky : IEnvironmentModel {
    public float3 evaluateEnvironment(EnvironmentQuery query) {
        float3 dir = query.direction;
        float clouds = dimensionClouds(dir * 3.5 + 31.0);
        float dust = dimensionClouds(dir * 12.0 + clouds * 2.0);
        float latitude = dot(dir, normalize(float3(0.35, 0.86, -0.37)));
        float veil = exp(-18.0 * pow(latitude + 0.16 * (clouds - 0.5), 2.0));
        float filaments = smoothstep(0.24, 0.72, clouds) * (0.3 + dust);
        float3 color = float3(0.006, 0.004, 0.018);
        color += lerp(float3(0.10, 0.018, 0.22), float3(0.035, 0.12, 0.18), dust)
                * veil * filaments;
        // Mirroring only the star lookup fills the void below the islands as well as the upper dome.
        color += stars(float3(dir.x, abs(dir.y), dir.z), float3(0, 1, 0), 0.0, 0.65,
                float3(1.0), 28.0, 0.91, float3(0.55, 0.70, 1.0), float3(1.0, 0.62, 0.88));
        return bt709ToAcesCg(color);
    }
};
