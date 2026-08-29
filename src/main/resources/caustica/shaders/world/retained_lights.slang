module retained_lights;

import bindings;
import world_common;

public static const uint RETAINED_LIGHT_RECTANGLE = 0u;
public static const uint RETAINED_LIGHT_POINT = 1u;
public static const uint RETAINED_LIGHT_SPOT = 2u;
public static const uint RETAINED_LIGHT_DISTANT = 3u;
public static const uint NEE_AT_HISTORY_VALID = 1u;
public static const uint NEE_AT_NO_LIGHT = 0xffffffffu;
public static const uint RETAINED_LIGHT_LINKED_EMITTER = 1u;
public static const uint NEE_AT_PROPOSAL_GLOBAL = 0u;
public static const uint NEE_AT_PROPOSAL_SCREEN_MIXTURE = 1u;

public struct DirectLightSample {
    public float3 direction;
    public float3 radianceOverShapePdf;
    public float maximumDistance;
    public uint lightIndex;
    public float proposalPdf;
    public float shapePdf;
    public bool linkedEmitter;
    public bool valid;
};

public uint retainedLightHash(uint value) {
    value ^= value >> 16u;
    value *= 0x7feb352du;
    value ^= value >> 15u;
    value *= 0x846ca68bu;
    return value ^ (value >> 16u);
}

public float retainedLightRandom(inout uint state) {
    state = retainedLightHash(state);
    return (float(state) + 0.5) * (1.0 / 4294967296.0);
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

float neeAtGlobalPdf(NeeAtState state, uint lightIndex) {
    ConstPtr<float2> entries = (ConstPtr<float2>)state.globalDistributionAddress;
    return lightIndex < state.lightCount ? entries[lightIndex].x : 0.0;
}

uint neeAtTileIndex(NeeAtState state, uint2 pixel) {
    uint2 tile = min(pixel / max(state.tileSize, 1u),
            uint2(max(state.tileCountX, 1u) - 1u, max(state.tileCountY, 1u) - 1u));
    return tile.y * state.tileCountX + tile.x;
}

bool neeAtLocalAvailable(NeeAtState state, uint2 pixel) {
    if ((state.flags & NEE_AT_HISTORY_VALID) == 0u || state.localSlotCount == 0u) return false;
    ConstPtr<uint2> entries = (ConstPtr<uint2>)state.localDistributionAddress;
    uint base = neeAtTileIndex(state, pixel) * state.localSlotCount;
    return entries[base + state.localSlotCount - 1u].y != 0u;
}

float neeAtLocalPdf(NeeAtState state, uint2 pixel, uint lightIndex) {
    if ((state.flags & NEE_AT_HISTORY_VALID) == 0u || state.localSlotCount == 0u) return 0.0;
    ConstPtr<uint2> entries = (ConstPtr<uint2>)state.localDistributionAddress;
    uint base = neeAtTileIndex(state, pixel) * state.localSlotCount;
    uint total = entries[base + state.localSlotCount - 1u].y;
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

uint neeAtSampleGlobal(NeeAtState state, float randomValue) {
    ConstPtr<float2> entries = (ConstPtr<float2>)state.globalDistributionAddress;
    uint low = 0u;
    uint high = state.lightCount;
    while (low < high) {
        uint middle = low + (high - low) / 2u;
        if (randomValue <= entries[middle].y) high = middle;
        else low = middle + 1u;
    }
    return min(low, state.lightCount - 1u);
}

uint neeAtSampleLocal(NeeAtState state, uint2 pixel, float randomValue) {
    ConstPtr<uint2> entries = (ConstPtr<uint2>)state.localDistributionAddress;
    uint base = neeAtTileIndex(state, pixel) * state.localSlotCount;
    uint total = entries[base + state.localSlotCount - 1u].y;
    if (total == 0u) return NEE_AT_NO_LIGHT;
    float target = randomValue * float(total);
    uint low = 0u;
    uint high = state.localSlotCount;
    while (low < high) {
        uint middle = low + (high - low) / 2u;
        if (float(entries[base + middle].y) > target) high = middle;
        else low = middle + 1u;
    }
    return low < state.localSlotCount ? entries[base + low].x : NEE_AT_NO_LIGHT;
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
    if (light.type == RETAINED_LIGHT_RECTANGLE) {
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
    float rayEpsilon = 0.002 / max(state.metersPerSceneUnit, 1.0e-8);
    sample.maximumDistance = max(distance - rayEpsilon, 0.5 * rayEpsilon);

    if (light.type != RETAINED_LIGHT_RECTANGLE
            && distance * state.metersPerSceneUnit > light.rangeMeters) return invalidDirectLight();
    if (light.type == RETAINED_LIGHT_RECTANGLE) {
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
        float3 up = normalize(light.axisV.xyz);
        float3 right = normalize(cross(forward, up));
        float3 fromLight = -sample.direction;
        float axial = dot(fromLight, forward);
        if (axial <= 0.0) return invalidDirectLight();
        float horizontal = dot(fromLight, right) / max(axial * tan(light.axisU.w), 1.0e-6);
        float vertical = dot(fromLight, up) / max(axial * tan(light.axisV.w), 1.0e-6);
        if (horizontal * horizontal + vertical * vertical > 1.0) return invalidDirectLight();
    }
    sample.radianceOverShapePdf = light.radiometry.xyz / max(distanceMetersSquared, 1.0e-8);
    return sample;
}

float neeAtProposalPdf(NeeAtState state, uint2 pixel, uint lightIndex,
                       uint candidateCount, bool allowLocal) {
    bool localAvailable = allowLocal && neeAtLocalAvailable(state, pixel);
    uint localDrawCount = localAvailable ? candidateCount / 2u : 0u;
    float localFraction = float(localDrawCount) / float(candidateCount);
    float globalFraction = float(candidateCount - localDrawCount) / float(candidateCount);
    return globalFraction * neeAtGlobalPdf(state, lightIndex)
            + localFraction * neeAtLocalPdf(state, pixel, lightIndex);
}

public float neeAtReverseRectanglePdf(WorldBindingRoots roots, uint2 pixel, uint lightIndex,
                                      float3 previousPosition, float3 hitPosition,
                                      uint proposalMode, uint candidateCount) {
    NeeAtState state = neeAtState(roots.neeAtStateAddress)[0];
    if (lightIndex >= state.lightCount) return 0.0;
    ConstPtr<RetainedLightRecord> records = (ConstPtr<RetainedLightRecord>)state.lightTableAddress;
    RetainedLightRecord light = records[lightIndex];
    if (light.type != RETAINED_LIGHT_RECTANGLE
            || (light.flags & RETAINED_LIGHT_LINKED_EMITTER) == 0u) return 0.0;
    float3 toHit = hitPosition - previousPosition;
    float distanceSquared = dot(toHit, toHit);
    float3 direction = normalize(toHit);
    float area = 4.0 * length(cross(light.axisU.xyz, light.axisV.xyz));
    float emitterCosine = dot(normalize(cross(light.axisU.xyz, light.axisV.xyz)), -direction);
    if (emitterCosine <= 0.0) return 0.0;
    return neeAtProposalPdf(state, pixel, lightIndex, max(candidateCount, 1u),
            proposalMode == NEE_AT_PROPOSAL_SCREEN_MIXTURE)
            * distanceSquared / max(emitterCosine * area, 1.0e-20);
}

public DirectLightSample sampleRetainedLight(WorldBindingRoots roots, float3 position, uint2 pixel,
                                              uint candidate, uint candidateCount, bool allowLocal,
                                              inout uint seed) {
    NeeAtState state = neeAtState(roots.neeAtStateAddress)[0];
    if (state.lightCount == 0u) return invalidDirectLight();
    bool localAvailable = allowLocal && neeAtLocalAvailable(state, pixel);
    bool drawLocal = localAvailable && (candidate & 1u) != 0u;
    uint lightIndex = drawLocal
            ? neeAtSampleLocal(state, pixel, retainedLightRandom(seed))
            : neeAtSampleGlobal(state, retainedLightRandom(seed));
    DirectLightSample sample = sampleRetainedLightIndex(state, lightIndex, position,
            retainedLightRandom(seed), retainedLightRandom(seed));
    if (!sample.valid) return sample;
    sample.proposalPdf = neeAtProposalPdf(state, pixel, lightIndex, candidateCount, allowLocal);
    if (sample.proposalPdf <= 0.0) return invalidDirectLight();
    return sample;
}

uint neeAtSaturatingAdd(DevicePtr<uint> values, uint index, uint increment) {
    uint observed = values[index];
    for (;;) {
        uint desired = observed + min(increment, 0xffffffffu - observed);
        uint original;
        InterlockedCompareExchange(values[index], observed, desired, original);
        if (original == observed) return observed;
        observed = original;
    }
}

public void neeAtWriteFeedback(WorldBindingRoots roots, uint2 pixel, uint lightIndex,
                               float contribution) {
    NeeAtState state = neeAtState(roots.neeAtStateAddress)[0];
    if (lightIndex >= state.lightCount || pixel.x >= state.extentWidth || pixel.y >= state.extentHeight) return;
    DevicePtr<uint> lightFeedback = (DevicePtr<uint>)state.lightFeedbackAddress;
    float finiteContribution = isfinite(contribution) ? max(contribution, 0.0) : 0.0;
    uint fixedWeight = max(1u, uint(min(finiteContribution * 256.0 + 0.5, 65535.0)));
    neeAtSaturatingAdd(lightFeedback, lightIndex, fixedWeight);

    uint pixelIndex = pixel.y * state.extentWidth + pixel.x;
    DevicePtr<uint> pixelWords = (DevicePtr<uint>)state.pixelFeedbackAddress;
    uint previousTotal = neeAtSaturatingAdd(pixelWords, pixelIndex * 2u + 1u, fixedWeight);
    uint total = previousTotal + min(fixedWeight, 0xffffffffu - previousTotal);
    uint selectionSeed = retainedLightHash(pixelIndex ^ state.frameIndex ^ lightIndex ^ previousTotal);
    if (retainedLightRandom(selectionSeed) * float(total) <= float(fixedWeight)) {
        pixelWords[pixelIndex * 2u] = lightIndex;
    }
}
