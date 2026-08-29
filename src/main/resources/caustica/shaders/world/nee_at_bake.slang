module nee_at_bake;

import world_common;
import caustica_resources;

struct NeeAtPlanRecord {
    uint currentToPrevious;
    uint previousToCurrent;
    float power;
};

public struct NeeAtBakePush {
    public uint64_t stateAddress;
    public uint64_t planAddress;
    public uint64_t previousLightFeedbackAddress;
    public uint64_t previousPixelFeedbackAddress;
    public uint phase;
    public uint historyValid;
};

uint neeAtBakeHash(uint value) {
    value ^= value >> 16u;
    value *= 0x7feb352du;
    value ^= value >> 15u;
    value *= 0x846ca68bu;
    return value ^ (value >> 16u);
}

groupshared uint localLights[128];
groupshared uint localMasses[128];
groupshared uint localScan[128];
groupshared float globalPowerSums[64];
groupshared float globalFeedbackSums[64];
groupshared float globalScan[64];
groupshared float globalScanNext[64];
groupshared float globalCarry;

void bakeGlobal(NeeAtState state, uint lane) {
    ConstPtr<NeeAtPlanRecord> plan = (ConstPtr<NeeAtPlanRecord>)neeAtBakePush.planAddress;
    ConstPtr<uint> previousFeedback =
            (ConstPtr<uint>)neeAtBakePush.previousLightFeedbackAddress;
    DevicePtr<uint> currentFeedback = (DevicePtr<uint>)state.lightFeedbackAddress;
    DevicePtr<float2> distribution = (DevicePtr<float2>)state.globalDistributionAddress;
    float lanePower = 0.0;
    float laneFeedback = 0.0;
    for (uint index = lane; index < state.lightCount; index += 64u) {
        lanePower += plan[index].power;
        uint previous = plan[index].currentToPrevious;
        if (neeAtBakePush.historyValid != 0u && previous < state.priorLightCount) {
            laneFeedback += float(previousFeedback[previous]);
        }
    }
    globalPowerSums[lane] = lanePower;
    globalFeedbackSums[lane] = laneFeedback;
    GroupMemoryBarrierWithGroupSync();
    for (uint offset = 32u; offset > 0u; offset >>= 1u) {
        if (lane < offset) {
            globalPowerSums[lane] += globalPowerSums[lane + offset];
            globalFeedbackSums[lane] += globalFeedbackSums[lane + offset];
        }
        GroupMemoryBarrierWithGroupSync();
    }
    float powerTotal = globalPowerSums[0];
    float feedbackTotal = globalFeedbackSums[0];
    if (lane == 0u) globalCarry = 0.0;
    GroupMemoryBarrierWithGroupSync();
    for (uint chunk = 0u; chunk < state.lightCount; chunk += 64u) {
        uint index = chunk + lane;
        float pdf = 0.0;
        if (index < state.lightCount) {
            float prior = plan[index].power / max(powerTotal, 1.0e-20);
            float historic = prior;
            uint previous = plan[index].currentToPrevious;
            if (neeAtBakePush.historyValid != 0u && feedbackTotal > 0.0) {
                historic = previous < state.priorLightCount
                        ? float(previousFeedback[previous]) / feedbackTotal : 0.0;
            }
            pdf = lerp(prior, historic, 0.75);
        }
        globalScan[lane] = pdf;
        GroupMemoryBarrierWithGroupSync();
        for (uint offset = 1u; offset < 64u; offset <<= 1u) {
            globalScanNext[lane] = globalScan[lane]
                    + (lane >= offset ? globalScan[lane - offset] : 0.0);
            GroupMemoryBarrierWithGroupSync();
            globalScan[lane] = globalScanNext[lane];
            GroupMemoryBarrierWithGroupSync();
        }
        float chunkBase = globalCarry;
        if (index < state.lightCount) {
            float cdf = index + 1u == state.lightCount ? 1.0 : chunkBase + globalScan[lane];
            distribution[index] = float2(pdf, cdf);
            currentFeedback[index] = 0u;
        }
        GroupMemoryBarrierWithGroupSync();
        if (lane == 0u) globalCarry = chunkBase + globalScan[63];
        GroupMemoryBarrierWithGroupSync();
    }
}

void bakeLocal(NeeAtState state, uint tileIndex, uint lane) {
    uint tileCount = state.tileCountX * state.tileCountY;
    if (tileIndex >= tileCount) return;
    DevicePtr<uint2> output = (DevicePtr<uint2>)state.localDistributionAddress;
    for (uint slot = lane; slot < state.localSlotCount; slot += 64u) {
        localLights[slot] = 0xffffffffu;
        localMasses[slot] = 0u;
    }
    GroupMemoryBarrierWithGroupSync();
    uint2 tile = uint2(tileIndex % state.tileCountX, tileIndex / state.tileCountX);
    uint2 jitter = uint2(neeAtBakeHash(state.frameIndex * 2u) % state.tileSize,
            neeAtBakeHash(state.frameIndex * 2u + 1u) % state.tileSize);
    ConstPtr<uint2> feedback = (ConstPtr<uint2>)neeAtBakePush.previousPixelFeedbackAddress;
    ConstPtr<NeeAtPlanRecord> plan = (ConstPtr<NeeAtPlanRecord>)neeAtBakePush.planAddress;
    uint pixelCount = state.extentWidth * state.extentHeight;
    if (neeAtBakePush.historyValid != 0u && state.priorLightCount != 0u
            && lane < state.tileSize * state.tileSize) {
        uint2 local = uint2(lane % state.tileSize, lane / state.tileSize);
        uint2 permutedLocal = (local + jitter) % state.tileSize;
        uint2 pixel = tile * state.tileSize + permutedLocal;
        bool inExtent = pixel.x < state.extentWidth && pixel.y < state.extentHeight;
        uint sourceIndex = inExtent ? pixel.y * state.extentWidth + pixel.x : pixelCount;
        uint2 event = inExtent ? feedback[sourceIndex] : uint2(0u);
        if (event.y != 0u && event.x < state.priorLightCount) {
            uint currentLight = plan[event.x].previousToCurrent;
            if (currentLight < state.lightCount) {
                uint slot = neeAtBakeHash(currentLight) % state.localSlotCount;
                for (uint probe = 0u; probe < state.localSlotCount; probe++) {
                    uint observed;
                    InterlockedCompareExchange(localLights[slot], 0xffffffffu, currentLight, observed);
                    if (observed == 0xffffffffu || observed == currentLight) {
                        InterlockedAdd(localMasses[slot], event.y);
                        break;
                    }
                    slot = (slot + 1u) % state.localSlotCount;
                }
            }
        }
    }
    GroupMemoryBarrierWithGroupSync();
    for (uint slot = lane; slot < state.localSlotCount; slot += 64u) {
        localScan[slot] = localMasses[slot];
    }
    GroupMemoryBarrierWithGroupSync();
    for (uint offset = 1u; offset < state.localSlotCount; offset <<= 1u) {
        for (uint slot = lane; slot < state.localSlotCount; slot += 64u) {
            uint prior = slot >= offset ? localScan[slot - offset] : 0u;
            localMasses[slot] = localScan[slot] + prior;
        }
        GroupMemoryBarrierWithGroupSync();
        for (uint slot = lane; slot < state.localSlotCount; slot += 64u) {
            localScan[slot] = localMasses[slot];
        }
        GroupMemoryBarrierWithGroupSync();
    }
    uint base = tileIndex * state.localSlotCount;
    for (uint slot = lane; slot < state.localSlotCount; slot += 64u) {
        output[base + slot] = uint2(localLights[slot], localScan[slot]);
    }
}

void clearPixelFeedback(NeeAtState state, uint pixelIndex) {
    uint count = state.extentWidth * state.extentHeight;
    if (pixelIndex >= count) return;
    DevicePtr<uint2> pixels = (DevicePtr<uint2>)state.pixelFeedbackAddress;
    pixels[pixelIndex] = uint2(0u);
}

[[vk::push_constant]] public NeeAtBakePush neeAtBakePush;

public void runNeeAtBake(uint groupIndex, uint lane, uint dispatchIndex) {
    NeeAtState state = ((ConstPtr<NeeAtState>)neeAtBakePush.stateAddress)[0];
    if (neeAtBakePush.phase == 0u) {
        if (groupIndex == 0u) bakeGlobal(state, lane);
    } else if (neeAtBakePush.phase == 1u) {
        bakeLocal(state, groupIndex, lane);
    } else {
        clearPixelFeedback(state, dispatchIndex);
    }
}
