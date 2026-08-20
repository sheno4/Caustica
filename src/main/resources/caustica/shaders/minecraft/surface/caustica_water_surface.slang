module caustica_water_surface;

import caustica_surface;
import caustica_types;
import caustica_minecraft_material;

static const float PI = 3.14159265359;
static const float WAVE_STRENGTH = 0.3;
static const float WAVE_SPEED = 0.8;
static const int WAVE_COUNT = 10;
static const float WAVE_G = 9.81;
static const float2 WAVE_WIND = normalize(float2(1.0, 0.35));
static const float WAVE_MEANDER = 1.1;
static const float MEDIUM_DENSITY = 0.1;
static const float3 MEDIUM_ABSORB_FLOOR = float3(0.015, 0.010, 0.008);
static const float CAUSTIC_EPS = 0.25;
static const float CAUSTIC_MAX = 6.0;

float waveLodWeight(float wavelength, float footprint) {
    float cyclesPerPixel = max(footprint, 0.0) / max(wavelength, 1.0e-4);
    return 1.0 - smoothstep(0.25, 0.5, cyclesPerPixel);
}

void waveSpectrum<let WITH_DT : int>(float2 p, float t, float footprint,
        out float2 grad, out float2 gradDt) {
    const float Q[WAVE_COUNT] = { 0.030, 0.035, 0.042, 0.050, 0.060,
            0.065, 0.065, 0.058, 0.048, 0.038 };
    t *= WAVE_SPEED;
    grad = float2(0.0);
    gradDt = float2(0.0);
    float lambda = 14.0;
    for (int i = 0; i < WAVE_COUNT; i++) {
        float lodWeight = waveLodWeight(lambda, footprint);
        if (lodWeight <= 0.0) break;
        float k = 2.0 * PI / lambda;
        float w = sqrt(WAVE_G * k);
        float spread = (0.15 + 0.11 * float(i)) * sin(float(i) * 2.4 + 1.3);
        float cs = cos(spread), sn = sin(spread);
        float2 d = float2(WAVE_WIND.x * cs - WAVE_WIND.y * sn,
                WAVE_WIND.x * sn + WAVE_WIND.y * cs);
        float ph = k * dot(d, p) - w * t + 1.7 * float(i) * float(i);
        float2 dph = k * d;
        float phDt = -w;
        float2 dphDt = float2(0.0);
        if (i < 4) {
            float2 pd = float2(-d.y, d.x);
            float wph = 0.4 * k * dot(pd, p) + 0.5 * w * t;
            ph += WAVE_MEANDER * sin(wph);
            dph += (WAVE_MEANDER * cos(wph) * 0.4 * k) * pd;
            if (WITH_DT != 0) {
                float wphDt = 0.5 * w;
                phDt += WAVE_MEANDER * cos(wph) * wphDt;
                dphDt = (-WAVE_MEANDER * sin(wph) * wphDt * 0.4 * k) * pd;
            }
        }
        float sharpness = 1.2 + 0.12 * float(i);
        float sinPh = sin(ph);
        float cosPh = cos(ph);
        float exponent = exp(sharpness * (sinPh - 1.0));
        float amplitude = lodWeight * (Q[i] / k) * sharpness;
        grad += amplitude * cosPh * exponent * dph;
        if (WITH_DT != 0) {
            gradDt += amplitude * exponent
                    * (phDt * (-sinPh + sharpness * cosPh * cosPh) * dph + cosPh * dphDt);
        }
        lambda /= 1.5;
    }
    grad *= WAVE_STRENGTH;
    gradDt *= WAVE_STRENGTH;
}

float2 waveGradient(float2 p, float t, float footprint = 0.0) {
    float2 grad, ignored;
    waveSpectrum<0>(p, t, footprint, grad, ignored);
    return grad;
}

void temporalWaveGradient(float2 p, float currentTime, float previousTime, float footprint,
        out float2 currentGrad, out float2 previousGrad) {
    float2 gradDt;
    waveSpectrum<1>(p, currentTime, footprint, currentGrad, gradDt);
    previousGrad = currentGrad - (currentTime - previousTime) * WAVE_SPEED * gradDt;
}

float2 causticLanding(float2 xz, float t, float3 incident, float height, float ior) {
    float2 gradient = waveGradient(xz, t);
    float3 normal = normalize(float3(-gradient.x, 1.0, -gradient.y));
    float3 refracted = refract(incident, normal, 1.0 / max(ior, 1.0));
    return xz + refracted.xz * (height / max(-refracted.y, 0.05));
}

float2 backsolveCausticSurface(float2 initialSurface, float2 receiver, float t,
        float3 incident, float height, float ior) {
    float2 p0 = causticLanding(initialSurface, t, incident, height, ior);
    float2 px = causticLanding(initialSurface + float2(CAUSTIC_EPS, 0.0), t,
            incident, height, ior);
    float2 pz = causticLanding(initialSurface + float2(0.0, CAUSTIC_EPS), t,
            incident, height, ior);
    float2 columnX = (px - p0) / CAUSTIC_EPS;
    float2 columnZ = (pz - p0) / CAUSTIC_EPS;
    float determinant = columnX.x * columnZ.y - columnX.y * columnZ.x;
    if (abs(determinant) < 1.0e-5) return initialSurface;
    float2 error = p0 - receiver;
    float2 step = float2(error.x * columnZ.y - error.y * columnZ.x,
            columnX.x * error.y - columnX.y * error.x) / determinant;
    float maximumStep = max(height, CAUSTIC_EPS);
    step *= min(1.0, maximumStep / max(length(step), 1.0e-5));
    return initialSurface - step;
}

public struct WaterSurface : ISurfaceModel {
    public override void evaluateSurface(SurfaceInput input, inout MaterialInput material) {
        evaluateMinecraftMaterial(input, material);
        if ((input.surfaceFlags & SURFACE_ANIMATION_ENABLED) == 0u
                || abs(input.geometricNormal.y) < 0.5) return;
        float2 domain = input.proceduralPosition.xz;
        if ((input.surfaceFlags & SURFACE_RECONSTRUCTION_ANCHOR) == 0u) {
            float2 gradient = waveGradient(domain, input.time, input.textureFootprint);
            float orientation = input.geometricNormal.y >= 0.0 ? 1.0 : -1.0;
            material.geometryNormal = orientation * normalize(float3(-gradient.x, 1.0, -gradient.y));
            material.previousGeometryNormal = material.geometryNormal;
            return;
        }
        float2 currentGradient, previousGradient;
        temporalWaveGradient(domain, input.time, input.previousTime, input.textureFootprint,
                currentGradient, previousGradient);
        float orientation = input.geometricNormal.y >= 0.0 ? 1.0 : -1.0;
        material.geometryNormal = orientation
                * normalize(float3(-currentGradient.x, 1.0, -currentGradient.y));
        material.previousGeometryNormal = orientation
                * normalize(float3(-previousGradient.x, 1.0, -previousGradient.y));
    }

    public override MediumProperties evaluateMedium(MediumInput input) {
        MediumProperties properties;
        properties.extinction = MEDIUM_ABSORB_FLOOR
                + MEDIUM_DENSITY * (float3(1.0) - clamp(input.sourceColor, 0.0, 1.0));
        properties.guideTransmittance = float3(1.0);
        properties.transmissionRayBias = 0.005;
        properties.flags = MEDIUM_TERMINATE_ON_MISS | MEDIUM_BOUNDARY_LIGHTING;
        return properties;
    }

    public override float3 evaluateMediumLighting(MediumLightingInput input) {
        if ((input.surfaceFlags & SURFACE_ANIMATION_ENABLED) == 0u) return float3(1.0);
        float fade = smoothstep(0.06, 0.18, input.lightDirection.y);
        if (fade <= 0.0) return float3(1.0);
        float height = max(input.boundaryDistance * input.lightDirection.y, 0.05);
        float3 incident = -input.lightDirection;
        float3 receiverPosition = input.boundaryPosition
                - input.lightDirection * input.boundaryDistance;
        float2 receiver = (input.proceduralPosition
                + receiverPosition - input.boundaryPosition).xz;
        float2 base = backsolveCausticSurface(input.proceduralPosition.xz, receiver,
                input.time, incident, height, input.mediumIor);
        float2 p0 = causticLanding(base, input.time, incident, height, input.mediumIor);
        float2 px = causticLanding(base + float2(CAUSTIC_EPS, 0.0), input.time,
                incident, height, input.mediumIor);
        float2 pz = causticLanding(base + float2(0.0, CAUSTIC_EPS), input.time,
                incident, height, input.mediumIor);
        float determinant = abs((px.x - p0.x) * (pz.y - p0.y)
                - (px.y - p0.y) * (pz.x - p0.x));
        float focus = (CAUSTIC_EPS * CAUSTIC_EPS) / max(determinant, 1.0e-5);
        // This biased cap trades rare near-singular energy spikes for bounded firefly variance.
        return float3(lerp(1.0, min(focus, CAUSTIC_MAX), fade));
    }
};
