// Real-time volumetric cumulus cloud layer for Caustica.
//
// The density model follows the production ideas described in Guerrilla Games' Nubis / Horizon
// volumetric-cloud presentations: a large-scale weather field, height-dependent cloud profiles,
// multi-octave shape noise, high-frequency erosion, Beer-Lambert extinction, directional light
// marching, and a strongly forward-scattering water-droplet phase function. The implementation is
// self-contained Slang and does not copy Alpha Piscium shader source or require its texture assets.
module clouds;

import world_common;
import sky;

public static const float CLOUD_KM_PER_BLOCK = 0.01; // matches RtComposite's sky altitude scale
public static const float CLOUD_BASE_KM = 1.15;
public static const float CLOUD_TOP_KM = 4.35;
public static const float CLOUD_MAX_TRACE_BLOCKS = 18000.0;
public static const int CLOUD_VIEW_STEPS = 96;
public static const int CLOUD_LIGHT_STEPS = 8;
public static const int CLOUD_SHADOW_STEPS = 12;
public static const float CLOUD_EXTINCTION_PER_BLOCK = 0.020;
public static const float CLOUD_SCATTERING_ALBEDO = 0.985;
public static const float CLOUD_COVERAGE = 0.56;
public static const float CLOUD_DENSITY_GAIN = 1.35;

public struct CloudResult {
    public float3 inScattering;
    public float transmittance;
};

float cloudHash11(float p) {
    p = frac(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return frac(p);
}

float cloudHash21(float2 p) {
    float3 p3 = frac(float3(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return frac((p3.x + p3.y) * p3.z);
}

float cloudHash31(float3 p) {
    p = frac(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return frac((p.x + p.y) * p.z);
}

float cloudNoise2(float2 p) {
    float2 i = floor(p);
    float2 f = frac(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = cloudHash21(i);
    float b = cloudHash21(i + float2(1.0, 0.0));
    float c = cloudHash21(i + float2(0.0, 1.0));
    float d = cloudHash21(i + float2(1.0, 1.0));
    return lerp(lerp(a, b, u.x), lerp(c, d, u.x), u.y);
}

float cloudNoise3(float3 p) {
    float3 i = floor(p);
    float3 f = frac(p);
    float3 u = f * f * (3.0 - 2.0 * f);

    float n000 = cloudHash31(i + float3(0.0, 0.0, 0.0));
    float n100 = cloudHash31(i + float3(1.0, 0.0, 0.0));
    float n010 = cloudHash31(i + float3(0.0, 1.0, 0.0));
    float n110 = cloudHash31(i + float3(1.0, 1.0, 0.0));
    float n001 = cloudHash31(i + float3(0.0, 0.0, 1.0));
    float n101 = cloudHash31(i + float3(1.0, 0.0, 1.0));
    float n011 = cloudHash31(i + float3(0.0, 1.0, 1.0));
    float n111 = cloudHash31(i + float3(1.0, 1.0, 1.0));

    float nx00 = lerp(n000, n100, u.x);
    float nx10 = lerp(n010, n110, u.x);
    float nx01 = lerp(n001, n101, u.x);
    float nx11 = lerp(n011, n111, u.x);
    return lerp(lerp(nx00, nx10, u.y), lerp(nx01, nx11, u.y), u.z);
}

float cloudFbm2(float2 p) {
    float v = 0.0;
    float a = 0.5;
    [unroll]
    for (int i = 0; i < 5; ++i) {
        v += a * cloudNoise2(p);
        p = float2(1.73 * p.x + 1.11 * p.y, -1.11 * p.x + 1.73 * p.y) + 13.7;
        a *= 0.5;
    }
    return v;
}

float cloudFbm3(float3 p) {
    float v = 0.0;
    float a = 0.5;
    [unroll]
    for (int i = 0; i < 4; ++i) {
        v += a * cloudNoise3(p);
        p = p * 2.03 + float3(17.1, 9.2, 13.7);
        a *= 0.5;
    }
    return v;
}

float cloudHeightProfile(float h, float column) {
    // Flat, optically dense bases with rapidly billowing tops. column raises tall cells while leaving
    // shallow cells as stratocumulus-like fragments.
    float bottom = smoothstep(0.015, 0.105, h);
    float topStart = lerp(0.38, 0.70, column);
    float top = 1.0 - smoothstep(topStart, 1.0, h);
    float anvil = 1.0 - 0.28 * smoothstep(0.68, 1.0, h);
    return bottom * top * anvil;
}

float2 cloudWorldXZ(float3 rebasedPos, WorldPush push) {
    // waterAnchor.xy is the terrain rebase origin modulo 4096 and already exists specifically to make
    // procedural world-space fields stable across camera/rebase motion. Reusing it avoids another ABI lane.
    return rebasedPos.xz + push.waterAnchor.xy;
}

float cloudAltitudeKm(float3 rebasedPos, WorldPush push, SkyState state) {
    float viewerAltitudeKm = state.viewerRadiusKm - ATMOS_BOTTOM_KM;
    return viewerAltitudeKm + (rebasedPos.y - push.camOffset.y) * CLOUD_KM_PER_BLOCK;
}

float cloudWeather(float2 worldXZ, float timeSeconds) {
    float2 wind = float2(0.014, 0.006) * timeSeconds;
    float2 p = worldXZ * 0.00042 + wind;
    float broad = cloudFbm2(p);
    float cells = cloudNoise2(p * 1.75 + 41.7);
    return saturate(broad * 0.78 + cells * 0.34 - 0.10);
}

float cloudDensity(float3 rebasedPos, WorldPush push, SkyState state, bool detailed) {
    float altitude = cloudAltitudeKm(rebasedPos, push, state);
    float h = saturate((altitude - CLOUD_BASE_KM) / (CLOUD_TOP_KM - CLOUD_BASE_KM));
    if (altitude <= CLOUD_BASE_KM || altitude >= CLOUD_TOP_KM) {
        return 0.0;
    }

    float2 worldXZ = cloudWorldXZ(rebasedPos, push);
    float timeSeconds = push.waterParams.w;
    float weather = cloudWeather(worldXZ, timeSeconds);
    float coverageThreshold = 1.0 - CLOUD_COVERAGE;
    float column = saturate((weather - coverageThreshold) / max(1.0 - coverageThreshold, 1.0e-3));
    if (column <= 0.0) {
        return 0.0;
    }

    // Large forms use kilometre-scale frequencies; y is stretched slightly to favor towering cumulus.
    float3 shapePos = float3(worldXZ.x, rebasedPos.y * 1.22, worldXZ.y) * 0.00105;
    shapePos += float3(timeSeconds * 0.010, 0.0, timeSeconds * 0.0045);
    float shape = cloudFbm3(shapePos);
    float profile = cloudHeightProfile(h, column);
    float base = column * profile;
    base = saturate(base * 1.28 + (shape - 0.49) * 0.72);

    if (!detailed || base <= 0.0) {
        return base * CLOUD_DENSITY_GAIN;
    }

    // High-frequency noise erodes the boundary instead of simply adding density. That produces the
    // cauliflower edge hierarchy that makes Nubis-style clouds read as real volumes rather than fog blobs.
    float3 detailPos = float3(worldXZ.x, rebasedPos.y * 1.45, worldXZ.y) * 0.0068;
    detailPos += float3(-timeSeconds * 0.025, timeSeconds * 0.003, timeSeconds * 0.012);
    float detail = cloudFbm3(detailPos);
    float edge = saturate(1.0 - base);
    base = saturate(base - (detail - 0.34) * edge * 0.48);

    // Slightly denser upper lobes and a dark, coherent base are important cues for scale.
    base *= lerp(0.86, 1.28, smoothstep(0.16, 0.78, h));
    return saturate(base * CLOUD_DENSITY_GAIN);
}

bool cloudLayerSegment(float3 ro, float3 rd, WorldPush push, SkyState state,
                       out float tNear, out float tFar) {
    float alt = cloudAltitudeKm(ro, push, state);
    float verticalKmPerBlock = rd.y * CLOUD_KM_PER_BLOCK;

    if (abs(verticalKmPerBlock) < 1.0e-6) {
        if (alt > CLOUD_BASE_KM && alt < CLOUD_TOP_KM) {
            tNear = 0.0;
            tFar = CLOUD_MAX_TRACE_BLOCKS;
            return true;
        }
        tNear = 0.0;
        tFar = 0.0;
        return false;
    }

    float t0 = (CLOUD_BASE_KM - alt) / verticalKmPerBlock;
    float t1 = (CLOUD_TOP_KM - alt) / verticalKmPerBlock;
    tNear = max(min(t0, t1), 0.0);
    tFar = min(max(t0, t1), CLOUD_MAX_TRACE_BLOCKS);
    return tFar > tNear;
}

float cloudHg(float cosTheta, float g) {
    float g2 = g * g;
    float denom = max(1.0 + g2 - 2.0 * g * cosTheta, 1.0e-4);
    return (1.0 - g2) / (4.0 * SKY_PI * pow(denom, 1.5));
}

float cloudPhase(float cosTheta) {
    // Broad droplet lobe plus a tight silver-lining lobe and a weak backscatter peak.
    return 0.70 * cloudHg(cosTheta, 0.70)
         + 0.22 * cloudHg(cosTheta, 0.92)
         + 0.08 * cloudHg(cosTheta, -0.25);
}

float cloudLightOpticalDepth(float3 p, float3 lightDir, WorldPush push, SkyState state) {
    float tNear;
    float tFar;
    if (!cloudLayerSegment(p, lightDir, push, state, tNear, tFar)) {
        return 0.0;
    }
    // Start at the sample itself; cloudLayerSegment may return zero while already inside the layer.
    float len = min(tFar, 1800.0);
    float stepLen = len / float(CLOUD_LIGHT_STEPS);
    float tau = 0.0;
    [loop]
    for (int i = 0; i < CLOUD_LIGHT_STEPS; ++i) {
        float t = (float(i) + 0.65) * stepLen;
        float d = cloudDensity(p + lightDir * t, push, state, i < 3);
        tau += d * CLOUD_EXTINCTION_PER_BLOCK * stepLen;
        if (tau > 12.0) {
            break;
        }
    }
    return tau;
}

public float cloudDirectionalTransmittance(float3 p, float3 lightDir, WorldPush push, SkyState state) {
    float tNear;
    float tFar;
    if (!cloudLayerSegment(p, lightDir, push, state, tNear, tFar)) {
        return 1.0;
    }
    float len = min(tFar - tNear, 2200.0);
    if (len <= 0.0) {
        return 1.0;
    }
    float stepLen = len / float(CLOUD_SHADOW_STEPS);
    float tau = 0.0;
    [loop]
    for (int i = 0; i < CLOUD_SHADOW_STEPS; ++i) {
        float t = tNear + (float(i) + 0.5) * stepLen;
        // Ground shadows deliberately use the coarse density field; high-frequency edge noise here would
        // alias badly because a single shadow sample covers many world pixels.
        float d = cloudDensity(p + lightDir * t, push, state, false);
        tau += d * CLOUD_EXTINCTION_PER_BLOCK * stepLen;
        if (tau > 10.0) {
            return 0.0;
        }
    }
    return exp(-tau);
}

public CloudResult renderClouds(float3 ro, float3 rd, WorldPush push, SkyState state,
                                Sampler2D transmittanceLut, float3 backgroundBt709) {
    CloudResult result;
    result.inScattering = float3(0.0);
    result.transmittance = 1.0;

    float tNear;
    float tFar;
    if (!cloudLayerSegment(ro, rd, push, state, tNear, tFar)) {
        return result;
    }

    CelestialLight light = dominantCelestialLight(state, transmittanceLut);
    float segmentLength = tFar - tNear;
    float stepLen = segmentLength / float(CLOUD_VIEW_STEPS);
    if (stepLen <= 0.0) {
        return result;
    }

    float jitter = cloudHash31(ro * 0.017 + rd * 31.7 + float3(float(push.frameIndex & 1023u)));
    float cosTheta = dot(rd, light.dir);
    float phase = cloudPhase(cosTheta);

    // Sky radiance itself is a stable ambient proxy; a small floor keeps moonless clouds from becoming
    // mathematically black while still letting them read almost black to the eye at night.
    float3 ambient = backgroundBt709 * 0.20 + float3(state.airglowLuminance * 1.8 + 0.0004);

    [loop]
    for (int i = 0; i < CLOUD_VIEW_STEPS; ++i) {
        float t = tNear + (float(i) + jitter) * stepLen;
        if (t >= tFar) {
            break;
        }
        float3 p = ro + rd * t;
        float density = cloudDensity(p, push, state, true);
        if (density <= 0.002) {
            continue;
        }

        float sigmaT = density * CLOUD_EXTINCTION_PER_BLOCK;
        float opticalDepth = sigmaT * stepLen;
        float stepTrans = exp(-opticalDepth);

        float lightTau = cloudLightOpticalDepth(p, light.dir, push, state);
        float lightTrans = exp(-lightTau);
        // Powder term brightens thin, sun-facing rims without inventing light in optically thick cores.
        float powder = 1.0 - exp(-density * stepLen * CLOUD_EXTINCTION_PER_BLOCK * 1.65);
        float3 direct = light.illuminance * lightTrans * phase * (0.42 + 0.58 * powder);

        // Approximate higher scattering orders by feeding a fraction of lost direct light back as an
        // isotropic component. This is intentionally energy-bounded and keeps thick cumulus interiors
        // luminous instead of the single-scattering black-core failure mode.
        float ms = (1.0 - lightTrans) * 0.12 + 0.035;
        float3 source = CLOUD_SCATTERING_ALBEDO * (direct + ambient * (1.0 + ms * 7.0)
                         + light.illuminance * (ms * ISOTROPIC_PHASE));

        float segmentIntegral = (1.0 - stepTrans) / max(sigmaT, 1.0e-6);
        result.inScattering += result.transmittance * source * sigmaT * segmentIntegral;
        result.transmittance *= stepTrans;
        if (result.transmittance < 0.004) {
            break;
        }
    }

    return result;
}
