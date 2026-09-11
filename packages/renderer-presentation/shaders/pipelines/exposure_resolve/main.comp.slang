// Single-invocation exposure controller: percentile-trimmed mean of log2 luminance -> key -> clamp ->
// exponential smoothing in EV space. ExposureState keeps temporal state and read-only diagnostics
// (evScene/evTarget/evApplied/clipLowFrac/clipHighFrac) on the GPU. The CPU advances resetSeq when
// scene continuity breaks; a mismatch snaps to this frame's target without a host write or fence.

import display_common;

[[vk::push_constant]] ExposureResolvePush pc;

static const float LOG_MIN = -12.0;
static const float LOG_MAX = 12.0;
static const float LOG_STEP = (LOG_MAX - LOG_MIN) / 256.0;

float binCenter(uint bin) {
    return LOG_MIN + (float(bin) + 0.5) * LOG_STEP;
}

float curveSegment(float x, float x0, float y0, float x1, float y1) {
    return lerp(y0, y1, (x - x0) / (x1 - x0));
}

float curveCompensation(float sceneEv) {
    if (sceneEv <= pc.curveScene0) return pc.curveCompensation0;
    if (sceneEv < pc.curveScene1) {
        return curveSegment(sceneEv, pc.curveScene0, pc.curveCompensation0,
                pc.curveScene1, pc.curveCompensation1);
    }
    if (sceneEv < pc.curveScene2) {
        return curveSegment(sceneEv, pc.curveScene1, pc.curveCompensation1,
                pc.curveScene2, pc.curveCompensation2);
    }
    if (sceneEv < pc.curveScene3) {
        return curveSegment(sceneEv, pc.curveScene2, pc.curveCompensation2,
                pc.curveScene3, pc.curveCompensation3);
    }
    return pc.curveCompensation3;
}

float curveEffectiveSlope(float sceneEv) {
    if (sceneEv <= pc.curveScene0 || sceneEv >= pc.curveScene3) return 1.0;
    float compensationSlope;
    if (sceneEv < pc.curveScene1) {
        compensationSlope = (pc.curveCompensation1 - pc.curveCompensation0)
                / (pc.curveScene1 - pc.curveScene0);
    } else if (sceneEv < pc.curveScene2) {
        compensationSlope = (pc.curveCompensation2 - pc.curveCompensation1)
                / (pc.curveScene2 - pc.curveScene1);
    } else {
        compensationSlope = (pc.curveCompensation3 - pc.curveCompensation2)
                / (pc.curveScene3 - pc.curveScene2);
    }
    return 1.0 - compensationSlope;
}

float2 populationScales(float surfacePopulation, float environmentPopulation, float emissivePopulation, float2 caps) {
    float environmentCap = clamp(caps.x, 0.0, 1.0);
    float emissiveCap = clamp(caps.y, 0.0, 1.0);
    float environmentScale = 1.0;
    float emissiveScale = 1.0;
    // A cap is fully active only once ordinary surfaces fill the share left by that cap. Before then,
    // blend continuously from uncapped metering. Views dominated by the environment stay exposed for it,
    // without a discontinuity when the first ordinary pixel enters a view of only environment or emission.
    if (surfacePopulation > 0.0) {
        float rawTotal = surfacePopulation + environmentPopulation + emissivePopulation;
        float surfaceShare = surfacePopulation / max(rawTotal, 1.0);
        bool capEnvironment = environmentPopulation > environmentCap * rawTotal;
        bool capEmissive = emissivePopulation > emissiveCap * rawTotal;
        float weightedEnvironment = environmentPopulation;
        float weightedEmissive = emissivePopulation;
        if (capEnvironment && capEmissive) {
            float ordinaryShare = max(1.0 - environmentCap - emissiveCap, 1.0e-6);
            float cappedTotal = surfacePopulation / ordinaryShare;
            weightedEnvironment = environmentCap * cappedTotal;
            weightedEmissive = emissiveCap * cappedTotal;
        } else if (capEnvironment) {
            weightedEnvironment = environmentCap <= 0.0 ? 0.0
                    : environmentCap * (surfacePopulation + emissivePopulation) / max(1.0 - environmentCap, 1.0e-6);
        } else if (capEmissive) {
            weightedEmissive = emissiveCap <= 0.0 ? 0.0
                    : emissiveCap * (surfacePopulation + environmentPopulation) / max(1.0 - emissiveCap, 1.0e-6);
        }
        float environmentCapStrength = smoothstep(0.0, max(1.0 - environmentCap, 1.0e-6), surfaceShare);
        float emissiveCapStrength = smoothstep(0.0, max(1.0 - emissiveCap, 1.0e-6), surfaceShare);
        weightedEnvironment = lerp(environmentPopulation, weightedEnvironment, environmentCapStrength);
        weightedEmissive = lerp(emissivePopulation, weightedEmissive, emissiveCapStrength);
        environmentScale = environmentPopulation > 0.0 ? min(1.0, weightedEnvironment / environmentPopulation) : 1.0;
        emissiveScale = emissivePopulation > 0.0
                ? min(1.0, weightedEmissive / emissivePopulation) : 1.0;
    }
    return float2(environmentScale, emissiveScale);
}

[shader("compute")]
[numthreads(1, 1, 1)]
void main() {
    DevicePtr<uint> histBins = DevicePtr<uint>(pc.histogramAddress);
    DevicePtr<ExposureState> stateBuf = DevicePtr<ExposureState>(pc.stateAddress);
    [format("r32f")] RWTexture2D<float> exposureImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.exposureImageIndex)];
    float surfacePopulation = 0.0;
    float environmentPopulation = 0.0;
    float emissivePopulation = 0.0;
    for (uint i = 0u; i < 256u; ++i) {
        surfacePopulation += float(histBins[i]);
        environmentPopulation += float(histBins[256u + i]);
        emissivePopulation += float(histBins[512u + i]);
    }
    float2 scales = populationScales(surfacePopulation, environmentPopulation, emissivePopulation,
            float2(pc.environmentWeightCap, pc.emissiveWeightCap));
    float environmentScale = scales.x;
    float emissiveScale = scales.y;
    float population = surfacePopulation + environmentPopulation * environmentScale
            + emissivePopulation * emissiveScale;
    float total = max(population, 1.0);
    float lowPercentile = clamp(min(pc.lowPercentile, pc.highPercentile), 0.0, 1.0);
    float highPercentile = clamp(max(pc.lowPercentile, pc.highPercentile), 0.0, 1.0);
    float lowCount = min(floor(total * lowPercentile), total - 1.0);
    float highCount = clamp(ceil(total * highPercentile), lowCount + 1.0, total);

    float cumulative = 0.0;
    float weightedLogLum = 0.0;
    float weightedCount = 0.0;
    for (uint i = 0u; i < 256u; ++i) {
        float count = float(histBins[i]) + float(histBins[256u + i]) * environmentScale
                + float(histBins[512u + i]) * emissiveScale;
        float start = cumulative;
        float end = cumulative + count;
        float takeStart = max(start, lowCount);
        float takeEnd = min(end, highCount);
        if (takeEnd > takeStart) {
            float take = takeEnd - takeStart;
            weightedLogLum += binCenter(i) * take;
            weightedCount += take;
        }
        cumulative = end;
    }

    float avgLogLum = weightedCount > 0.0 ? weightedLogLum / weightedCount : 0.0;
    // Remove pre-exposure and convert stored log2 luminance to the curve's absolute EV100 scale.
    float evScene = avgLogLum + pc.evOffset;
    float compensation = curveCompensation(evScene);
    float effectiveSlope = curveEffectiveSlope(evScene);
    // Stored luminance times this residual equals key * 2^(evBias + compensation).
    float residualTarget = pc.key * exp2(pc.evBias - avgLogLum + compensation);
    // Clamp and smooth absolute exposure so changes to frame pre-exposure do not disturb history.
    float preExposure = (isfinite(pc.preExposure) && pc.preExposure > 0.0) ? pc.preExposure : 1.0;
    float target = clamp(residualTarget * preExposure, exp2(pc.minEv), exp2(pc.maxEv));

    bool initialized = stateBuf[0].initialized != 0u;
    bool reset = !initialized || stateBuf[0].resetSeq != pc.resetSeq;
    float targetEv = log2(max(target, 1.0e-12));
    if (reset) {
        stateBuf[0].resetSeq = pc.resetSeq;
    }
    float prevEv = reset ? targetEv : log2(max(stateBuf[0].previous, 1.0e-12));

    // EV-space smoothing gives equal relative convergence for brightening and darkening;
    // the scene-specific time constants control adaptation asymmetry.
    float exposureEv = prevEv;
    if (!reset) {
        float deltaEv = targetEv - prevEv;
        float rate = deltaEv > 0.0 ? pc.adaptDarken : pc.adaptBrighten;
        float alpha = 1.0 - exp(-pc.frameTimeSeconds / max(rate, 1.0e-4));
        exposureEv = prevEv + deltaEv * clamp(alpha, 0.0, 1.0);
    }
    float exposure = exp2(exposureEv);

    // Absolute exposure is both smoothing history and feedback for a later frame's pre-exposure.
    stateBuf[0].previous = exposure;
    stateBuf[0].initialized = 1u;
    stateBuf[0].evScene = evScene;
    stateBuf[0].evTarget = targetEv;
    stateBuf[0].evApplied = exposureEv;
    stateBuf[0].clipLowFrac = (float(histBins[0]) + float(histBins[256]) * environmentScale
            + float(histBins[512]) * emissiveScale) / total;
    stateBuf[0].clipHighFrac = (float(histBins[255]) + float(histBins[511]) * environmentScale
            + float(histBins[767]) * emissiveScale) / total;
    stateBuf[0].meteringEnvironmentScale = environmentScale;
    stateBuf[0].meteringEnvironmentFrac = environmentPopulation * environmentScale / total;
    stateBuf[0].curveCompensation = compensation;
    stateBuf[0].effectiveSlope = effectiveSlope;
    stateBuf[0].meteringEmissiveScale = emissiveScale;
    stateBuf[0].meteringEmissiveFrac = emissivePopulation * emissiveScale / total;
    // The display pass consumes stored radiance, which raygen already scaled by preExposure, so it
    // needs the residual rather than the absolute exposure. stored * residual == L * exposure.
    exposureImage[int2(0, 0)] = exposure / preExposure;
}
