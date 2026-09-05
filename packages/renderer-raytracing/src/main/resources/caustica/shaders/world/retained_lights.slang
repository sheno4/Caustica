module retained_lights;

import bindings;
import world_common;

public static const uint RETAINED_LIGHT_PARALLELOGRAM = 0u;
public static const uint RETAINED_LIGHT_SPOT = 1u;
public static const uint RETAINED_LIGHT_DISTANT = 2u;
public static const uint NEE_AT_NO_LIGHT = 0xffffffffu;
public static const uint RETAINED_LIGHT_LINKED_EMITTER = 1u;
public static const uint NEE_AT_PROPOSAL_GLOBAL = 0u;
public static const uint NEE_AT_PROPOSAL_SCREEN_MIXTURE = 1u;
// Ceiling on a pixel reservoir's running weight. Keeps the float total finite when a pixel sees an
// extremely bright light across many vertices; the reservoir stays well defined at the ceiling.
public static const float NEE_AT_MAX_FEEDBACK_WEIGHT = 1.0e12;

public struct DirectLightSample {
    public float3 direction;
    public float3 radianceOverShapePdf;
    public float maximumDistance;
    public float signalDistance;
    public uint lightIndex;
    public float proposalPdf;
    public float shapePdf;
    public bool linkedEmitter;
    public bool valid;
};

public uint retainedLightHash(uint value) {
    return neeAtHash(value);
}

public float retainedLightRandom(inout uint state) {
    state = retainedLightHash(state);
    return float(state >> 8u) * (1.0 / 16777216.0);
}

void retainedLightBasis(float3 normal, out float3 tangent, out float3 bitangent) {
    float3 up = abs(normal.z) < 0.999 ? float3(0.0, 0.0, 1.0) : float3(1.0, 0.0, 0.0);
    tangent = normalize(cross(up, normal));
    bitangent = cross(normal, tangent);
}

public DirectLightSample invalidDirectLight() {
    DirectLightSample sample = {};
    sample.lightIndex = NEE_AT_NO_LIGHT;
    sample.valid = false;
    return sample;
}

uint neeAtProxyTotal(NeeAtState state) {
    ConstPtr<uint2> entries = (ConstPtr<uint2>)state.globalDistributionAddress;
    return entries[state.lightCount].y;
}

float neeAtGlobalPdf(NeeAtState state, uint lightIndex, uint total) {
    if (lightIndex >= state.lightCount) return 0.0;
    if (total == 0u) return 0.0;
    ConstPtr<uint2> entries = (ConstPtr<uint2>)state.globalDistributionAddress;
    return float(entries[lightIndex].x) / float(total);
}

float neeAtGlobalPdf(NeeAtState state, uint lightIndex) {
    if (lightIndex >= state.lightCount) return 0.0;
    return neeAtGlobalPdf(state, lightIndex, neeAtProxyTotal(state));
}

// Number of candidates drawn from the local distribution; the rest come from the global one. Keeping
// at least one global candidate is what guarantees every light stays reachable from every pixel.
public uint neeAtLocalCandidateCount(NeeAtState state, uint candidateCount) {
    float ratio = clamp(state.localToGlobalRatio, 0.0, 1.0);
    return min(uint(float(candidateCount - 1u) * ratio + 0.75), candidateCount - 1u);
}

// Density the light-sampling technique must report when weighted against BSDF sampling. NEE draws
// `candidateCount` candidates per shading point, so its effective density is that many times the
// single-draw proposal density. Both MIS directions route through here so they cannot disagree.
public float neeAtLightMisPdf(float proposalPdf, float shapePdf, uint candidateCount) {
    return proposalPdf * shapePdf * float(max(candidateCount, 1u));
}

uint neeAtTileIndex(NeeAtState state, uint2 pixel) {
    uint tileSize = max(state.tileSize, 1u);
    uint2 jitter = uint2(retainedLightHash(state.frameIndex * 2u) % tileSize,
            retainedLightHash(state.frameIndex * 2u + 1u) % tileSize);
    uint2 tileCount = uint2(max(state.tileCountX, 1u), max(state.tileCountY, 1u));
    uint2 tile = ((pixel + jitter) % (tileCount * tileSize)) / tileSize;
    return tile.y * state.tileCountX + tile.x;
}

bool neeAtLocalAvailable(NeeAtState state, uint2 pixel) {
    if ((state.flags & NEE_AT_LOCAL_HISTORY_VALID) == 0u || state.localSlotCount == 0u) return false;
    ConstPtr<uint2> entries = (ConstPtr<uint2>)state.localDistributionAddress;
    uint base = neeAtTileIndex(state, pixel) * state.localSlotCount;
    return entries[base + state.localSlotCount - 1u].y != 0u;
}

float neeAtLocalPdf(NeeAtState state, uint lightIndex, uint base, uint total) {
    ConstPtr<uint2> entries = (ConstPtr<uint2>)state.localDistributionAddress;
    if (total == 0u) return 0.0;
    uint slot = retainedLightHash(lightIndex) % state.localSlotCount;
    for (uint probe = 0u; probe < state.localSlotCount; probe++) {
        uint2 entry = entries[base + slot];
        if (entry.x == lightIndex) {
            uint previous = slot == 0u ? 0u : entries[base + slot - 1u].y;
            return float(entry.y - previous) / float(total);
        }
        if (entry.x == NEE_AT_NO_LIGHT) return 0.0;
        slot = (slot + 1u) % state.localSlotCount;
    }
    return 0.0;
}

float neeAtLocalPdf(NeeAtState state, uint2 pixel, uint lightIndex) {
    if ((state.flags & NEE_AT_LOCAL_HISTORY_VALID) == 0u || state.localSlotCount == 0u) return 0.0;
    ConstPtr<uint2> entries = (ConstPtr<uint2>)state.localDistributionAddress;
    uint base = neeAtTileIndex(state, pixel) * state.localSlotCount;
    uint total = entries[base + state.localSlotCount - 1u].y;
    return neeAtLocalPdf(state, lightIndex, base, total);
}

// One indexed load: the proxy array already encodes the distribution, so there is no search and no
// divergence between lanes drawing different lights.
uint neeAtSampleGlobal(NeeAtState state, uint total, float randomValue) {
    if (total == 0u) return NEE_AT_NO_LIGHT;
    ConstPtr<uint> proxies = (ConstPtr<uint>)state.proxyIndexAddress;
    return proxies[min(uint(randomValue * float(total)), total - 1u)];
}

uint neeAtSampleLocal(NeeAtState state, uint base, uint total, float randomValue, out float pdf) {
    pdf = 0.0;
    ConstPtr<uint2> entries = (ConstPtr<uint2>)state.localDistributionAddress;
    if (total == 0u) return NEE_AT_NO_LIGHT;
    float target = randomValue * float(total);
    uint low = 0u;
    uint high = state.localSlotCount;
    while (low < high) {
        uint middle = low + (high - low) / 2u;
        if (float(entries[base + middle].y) > target) high = middle;
        else low = middle + 1u;
    }
    if (low >= state.localSlotCount) return NEE_AT_NO_LIGHT;
    uint2 entry = entries[base + low];
    uint previous = low == 0u ? 0u : entries[base + low - 1u].y;
    // Each light occupies one slot, so this CDF interval is also its local PDF numerator.
    pdf = float(entry.y - previous) / float(total);
    return entry.x;
}

DirectLightSample sampleRetainedLightIndex(NeeAtState state, uint lightIndex,
                                           float3 position, float randomU, float randomV) {
    if (lightIndex >= state.lightCount) return invalidDirectLight();
    ConstPtr<RetainedLightRecord> records = (ConstPtr<RetainedLightRecord>)state.lightTableAddress;
    RetainedLightRecord light = records[lightIndex];
    DirectLightSample sample = {};
    sample.lightIndex = lightIndex;
    sample.linkedEmitter = (light.flags & RETAINED_LIGHT_LINKED_EMITTER) != 0u;
    sample.valid = true;
    sample.maximumDistance = 1.0e20;

    if (light.type == RETAINED_LIGHT_DISTANT) {
        float3 sourceDirection = normalize(light.positionOrDirection.xyz);
        float radius = max(light.angularRadius, 0.0);
        if (radius == 0.0) {
            sample.direction = sourceDirection;
            sample.radianceOverShapePdf = light.radiometry.xyz;
            return sample;
        }
        float cosRadius = cos(radius);
        float cosTheta = lerp(1.0, cosRadius, randomU);
        float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));
        float phi = 6.28318530718 * randomV;
        float3 tangent;
        float3 bitangent;
        retainedLightBasis(sourceDirection, tangent, bitangent);
        sample.direction = normalize(sourceDirection * cosTheta
                + tangent * (cos(phi) * sinTheta) + bitangent * (sin(phi) * sinTheta));
        sample.radianceOverShapePdf = light.radiometry.xyz
                * (2.0 / max(1.0 + cosRadius, 1.0e-6));
        return sample;
    }

    float3 lightPosition = light.positionOrDirection.xyz;
    if (light.type == RETAINED_LIGHT_PARALLELOGRAM) {
        lightPosition += light.axisU.xyz * (2.0 * randomU - 1.0)
                + light.axisV.xyz * (2.0 * randomV - 1.0);
    }
    float3 toLight = lightPosition - position;
    float distanceSquared = dot(toLight, toLight);
    if (distanceSquared <= 1.0e-10) return invalidDirectLight();
    float distance = sqrt(distanceSquared);
    float distanceMetersSquared = distanceSquared
            * state.metersPerSceneUnit * state.metersPerSceneUnit;
    sample.direction = toLight / distance;
    sample.signalDistance = distance;
    float rayEpsilon = 0.002 / max(state.metersPerSceneUnit, 1.0e-8);
    sample.maximumDistance = max(distance - rayEpsilon, 0.5 * rayEpsilon);

    if (light.type != RETAINED_LIGHT_PARALLELOGRAM
            && distance * state.metersPerSceneUnit > light.rangeMeters) return invalidDirectLight();
    if (light.type == RETAINED_LIGHT_PARALLELOGRAM) {
        float3 crossAxes = cross(light.axisU.xyz, light.axisV.xyz);
        float emitterCosine = dot(normalize(crossAxes), -sample.direction);
        if (emitterCosine <= 0.0) return invalidDirectLight();
        float area = 4.0 * length(crossAxes);
        sample.shapePdf = distanceSquared / max(emitterCosine * area, 1.0e-20);
        sample.radianceOverShapePdf = light.radiometry.xyz
                * (emitterCosine * area / distanceSquared);
        return sample;
    }
    if (light.type == RETAINED_LIGHT_SPOT) {
        float3 forward = normalize(light.axisU.xyz);
        float3 fromLight = -sample.direction;
        if (dot(fromLight, forward) < cos(light.axisU.w)) return invalidDirectLight();
    }
    sample.radianceOverShapePdf = light.radiometry.xyz / max(distanceMetersSquared, 1.0e-8);
    return sample;
}

float neeAtProposalPdf(NeeAtState state, uint2 pixel, uint lightIndex,
                       uint candidateCount, bool allowLocal) {
    bool localAvailable = allowLocal && neeAtLocalAvailable(state, pixel);
    uint localDrawCount = localAvailable ? neeAtLocalCandidateCount(state, candidateCount) : 0u;
    if (localDrawCount == 0u) return neeAtGlobalPdf(state, lightIndex);
    float localFraction = float(localDrawCount) / float(candidateCount);
    float globalFraction = float(candidateCount - localDrawCount) / float(candidateCount);
    return globalFraction * neeAtGlobalPdf(state, lightIndex)
            + localFraction * neeAtLocalPdf(state, pixel, lightIndex);
}

public float neeAtReverseParallelogramPdf(WorldBindingRoots roots, uint2 pixel, uint lightIndex,
                                      float3 previousPosition, float3 hitPosition,
                                      uint proposalMode, uint candidateCount) {
    NeeAtState state = neeAtState(roots.neeAtStateAddress)[0];
    if (lightIndex >= state.lightCount) return 0.0;
    ConstPtr<RetainedLightRecord> records = (ConstPtr<RetainedLightRecord>)state.lightTableAddress;
    RetainedLightRecord light = records[lightIndex];
    if (light.type != RETAINED_LIGHT_PARALLELOGRAM
            || (light.flags & RETAINED_LIGHT_LINKED_EMITTER) == 0u) return 0.0;
    float3 fromCenter = hitPosition - light.positionOrDirection.xyz;
    float uu = dot(light.axisU.xyz, light.axisU.xyz);
    float uv = dot(light.axisU.xyz, light.axisV.xyz);
    float vv = dot(light.axisV.xyz, light.axisV.xyz);
    float determinant = uu * vv - uv * uv;
    if (determinant <= 0.0) return 0.0;
    float3 crossAxes = cross(light.axisU.xyz, light.axisV.xyz);
    float planeDistance = abs(dot(fromCenter, crossAxes)) / sqrt(determinant);
    if (planeDistance > 1.0e-4 * sqrt(max(uu, vv))) return 0.0;
    float2 supportCoordinates = float2(
            (dot(fromCenter, light.axisU.xyz) * vv
                    - dot(fromCenter, light.axisV.xyz) * uv) / determinant,
            (dot(fromCenter, light.axisV.xyz) * uu
                    - dot(fromCenter, light.axisU.xyz) * uv) / determinant);
    if (any(abs(supportCoordinates) > float2(1.0001))) return 0.0;
    float3 toHit = hitPosition - previousPosition;
    float distanceSquared = dot(toHit, toHit);
    float3 direction = normalize(toHit);
    float area = 4.0 * length(crossAxes);
    float emitterCosine = dot(normalize(crossAxes), -direction);
    if (emitterCosine <= 0.0) return 0.0;
    uint candidates = max(candidateCount, 1u);
    float proposalPdf = neeAtProposalPdf(state, pixel, lightIndex, candidates,
            proposalMode == NEE_AT_PROPOSAL_SCREEN_MIXTURE);
    float shapePdf = distanceSquared / max(emitterCosine * area, 1.0e-20);
    return neeAtLightMisPdf(proposalPdf, shapePdf, candidates);
}

// Distribution state is immutable throughout all candidate draws at one shading vertex.
public struct RetainedLightSamplingContext {
    public NeeAtState state;
    public uint globalTotal;
    public uint localBase;
    public uint localTotal;
    public uint localDrawCount;
    public uint globalDrawCount;
    public float localFraction;
    public float globalFraction;
};

public RetainedLightSamplingContext retainedLightSamplingContext(NeeAtState state, uint2 pixel,
        uint candidateCount, bool allowLocal) {
    RetainedLightSamplingContext context = {};
    context.state = state;
    if (state.lightCount == 0u) return context;
    context.globalTotal = neeAtProxyTotal(state);
    if (allowLocal && (state.flags & NEE_AT_LOCAL_HISTORY_VALID) != 0u && state.localSlotCount != 0u) {
        ConstPtr<uint2> entries = (ConstPtr<uint2>)state.localDistributionAddress;
        context.localBase = neeAtTileIndex(state, pixel) * state.localSlotCount;
        context.localTotal = entries[context.localBase + state.localSlotCount - 1u].y;
    }
    context.localDrawCount = context.localTotal != 0u
            ? neeAtLocalCandidateCount(state, candidateCount) : 0u;
    context.globalDrawCount = candidateCount - context.localDrawCount;
    context.localFraction = float(context.localDrawCount) / float(candidateCount);
    context.globalFraction = float(candidateCount - context.localDrawCount) / float(candidateCount);
    return context;
}

public DirectLightSample sampleRetainedLight(RetainedLightSamplingContext context, float3 position,
                                              uint candidate, inout uint seed) {
    NeeAtState state = context.state;
    if (state.lightCount == 0u) return invalidDirectLight();
    bool drawLocal = candidate >= context.globalDrawCount;
    float sampledLocalPdf = 0.0;
    uint lightIndex = drawLocal
            ? neeAtSampleLocal(state, context.localBase, context.localTotal,
                    retainedLightRandom(seed), sampledLocalPdf)
            : neeAtSampleGlobal(state, context.globalTotal, retainedLightRandom(seed));
    DirectLightSample sample = sampleRetainedLightIndex(state, lightIndex, position,
            retainedLightRandom(seed), retainedLightRandom(seed));
    if (!sample.valid) return sample;
    float globalPdf = neeAtGlobalPdf(state, lightIndex, context.globalTotal);
    if (context.localDrawCount != 0u) {
        float localPdf = drawLocal ? sampledLocalPdf
                : neeAtLocalPdf(state, lightIndex, context.localBase, context.localTotal);
        sample.proposalPdf = context.globalFraction * globalPdf
                + context.localFraction * localPdf;
    } else {
        sample.proposalPdf = globalPdf;
    }
    if (sample.proposalPdf <= 0.0) return invalidDirectLight();
    return sample;
}

public void neeAtWriteFeedback(WorldBindingRoots roots, uint2 pixel, uint lightIndex,
                               float contribution, bool coherent) {
    NeeAtState state = neeAtState(roots.neeAtStateAddress)[0];
    if (lightIndex >= state.lightCount || pixel.x >= state.extentWidth || pixel.y >= state.extentHeight) return;
    if (!isfinite(contribution) || contribution <= 0.0) return;
    // Bias towards globally improbable lights: without it a light that is weak globally but dominant
    // in this tile rarely wins its pixel reservoir, and the local distribution converges to a copy of
    // the global one instead of complementing it.
    float globalPdf = neeAtGlobalPdf(state, lightIndex);
    float weight = globalPdf > 0.0 ? contribution / pow(globalPdf, 0.65) : contribution;
    if (!isfinite(weight) || weight <= 0.0) return;
    weight = min(weight, NEE_AT_MAX_FEEDBACK_WEIGHT);

    uint pixelIndex = pixel.y * state.extentWidth + pixel.x;
    DevicePtr<uint2> pixelFeedback = (DevicePtr<uint2>)state.pixelFeedbackAddress;
    // Weighted reservoir sampling over the vertices that lit this pixel. One ray-generation thread
    // owns each pixel and the tracing passes that reach this are separated by a barrier, so the
    // read-modify-write needs no atomics. `.y` carries the running weight as raw float bits, which a
    // zero-filled buffer reads back as 0.0.
    uint2 event = pixelFeedback[pixelIndex];
    float total = min(asfloat(event.y) + weight, NEE_AT_MAX_FEEDBACK_WEIGHT);
    uint selectionSeed = retainedLightHash(pixelIndex ^ state.frameIndex ^ lightIndex ^ event.y) | 1u;
    if (retainedLightRandom(selectionSeed) * total <= weight) event.x = lightIndex | (coherent ? 0x80000000u : 0u);
    event.y = asuint(total);
    pixelFeedback[pixelIndex] = event;
}
