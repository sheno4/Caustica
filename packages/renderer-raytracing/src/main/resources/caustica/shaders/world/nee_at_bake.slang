module nee_at_bake;

import world_common;
import caustica_resources;

struct NeeAtLightIdentity {
    uint64_t identity;
    uint denseIndex;
    uint reserved;
};

public struct NeeAtBakePush {
    public uint64_t stateAddress;
    public uint64_t powerAddress;
    public uint64_t previousPixelFeedbackAddress;
    public uint64_t blockSumAddress;
    public uint64_t previousDepthAddress;
    public uint64_t currentDepthAddress;
    public uint phase;
    public uint historyValid;
    public uint currentLinearDepthIndex;
    public uint currentMotionIndex;
    public uint64_t previousIdentitiesAddress;
    public uint64_t currentIdentityLookupAddress;
    public uint currentIdentityLookupMask;
    public uint reserved;
};

groupshared uint localLights[128];
groupshared uint localMasses[128];
groupshared uint localScan[128];
groupshared uint blockScan[64];
groupshared uint blockCarry;

// Every lane participates, including padded lanes with value zero. Each barrier separates reads
// of the previous scan step from writes to the next one.
uint inclusiveBlockScan(uint lane, uint value) {
    blockScan[lane] = value;
    GroupMemoryBarrierWithGroupSync();
    for (uint offset = 1u; offset < 64u; offset <<= 1u) {
        uint next = blockScan[lane] + (lane >= offset ? blockScan[lane - offset] : 0u);
        GroupMemoryBarrierWithGroupSync();
        blockScan[lane] = next;
        GroupMemoryBarrierWithGroupSync();
    }
    return blockScan[lane];
}

// Feedback indices belong to the actual previous submitted frame's immutable dense identity table.
uint neeAtCurrentLight(uint previousIndex) {
    ConstPtr<uint64_t> previous = (ConstPtr<uint64_t>)neeAtBakePush.previousIdentitiesAddress;
    uint64_t identity = previous[previousIndex];
    ConstPtr<NeeAtLightIdentity> current = (ConstPtr<NeeAtLightIdentity>)neeAtBakePush.currentIdentityLookupAddress;
    uint mask = neeAtBakePush.currentIdentityLookupMask;
    uint slot = neeAtHash(uint(identity) ^ uint(identity >> 32u)) & mask;
    for (;;) {
        NeeAtLightIdentity entry = current[slot];
        if (entry.identity == 0ull) return 0xffffffffu;
        if (entry.identity == identity) return entry.denseIndex;
        slot = (slot + 1u) & mask;
    }
}

uint neeAtBlockCount(NeeAtState state) {
    return (state.lightCount + 63u) / 64u;
}

// Number of pixels that contributed a usable light pick last frame. Pixels without one were counted
// into the slot past the last light, so this needs no second reduction over the per-light counts.
float neeAtFeedbackTotal(NeeAtState state) {
    if (neeAtBakePush.historyValid == 0u) return 0.0;
    ConstPtr<uint> counts = (ConstPtr<uint>)state.lightFeedbackAddress;
    float pixels = float(state.extentWidth) * float(state.extentHeight);
    return max(pixels - float(counts[state.lightCount]), 0.0);
}

void collectGlobalFeedback(NeeAtState state, uint pixelIndex) {
    uint pixelCount = state.extentWidth * state.extentHeight;
    if (pixelIndex >= pixelCount) return;
    ConstPtr<uint2> feedback = (ConstPtr<uint2>)neeAtBakePush.previousPixelFeedbackAddress;
    uint2 event = feedback[pixelIndex];
    uint slot = state.lightCount;
    uint previousLight = event.x & NEE_AT_FEEDBACK_LIGHT_MASK;
    if (asfloat(event.y) > 0.0 && previousLight < state.priorLightCount) {
        uint mapped = neeAtCurrentLight(previousLight);
        if (mapped < state.lightCount) slot = mapped;
    }
    DevicePtr<uint> counts = (DevicePtr<uint>)state.lightFeedbackAddress;
    InterlockedAdd(counts[slot], 1u);
}

// One thread per light: turn the blended prior into a sampling proxy count, and reduce each block's
// counts so the next pass can turn block totals into base offsets.
void computeProxyCounts(NeeAtState state, uint group, uint lane, uint lightIndex) {
    DevicePtr<uint2> distribution = (DevicePtr<uint2>)state.globalDistributionAddress;
    DevicePtr<uint> blockSums = (DevicePtr<uint>)neeAtBakePush.blockSumAddress;
    ConstPtr<float> powers = (ConstPtr<float>)neeAtBakePush.powerAddress;
    ConstPtr<uint> feedback = (ConstPtr<uint>)state.lightFeedbackAddress;
    float feedbackTotal = neeAtFeedbackTotal(state);
    uint count = 0u;
    if (lightIndex < state.lightCount) {
        float prior = powers[lightIndex] / max(state.powerTotal, 1.0e-20);
        float historic = feedbackTotal > 0.0 ? float(feedback[lightIndex]) / feedbackTotal : prior;
        float pdf = clamp(lerp(prior, historic, NEE_AT_GLOBAL_FEEDBACK_WEIGHT), 0.0, 1.0);
        // The proxy buffer holds NEE_AT_PROXY_RATIO entries per light and the sampler indexes it by
        // the total published below, so the total must not exceed that however the arithmetic
        // rounds. Two entries per light of headroom cover it: one for rounding each count up, one
        // for the pdf sum landing slightly above 1 after per-light divisions. The floor of one proxy
        // keeps every light reachable however small its share rounds.
        float budget = float(state.lightCount * (NEE_AT_PROXY_RATIO - 2u));
        count = max(1u, uint(ceil(budget * pdf)));
        distribution[lightIndex] = uint2(count, 0u);
    }
    blockScan[lane] = count;
    GroupMemoryBarrierWithGroupSync();
    for (uint offset = 32u; offset > 0u; offset >>= 1u) {
        if (lane < offset) blockScan[lane] += blockScan[lane + offset];
        GroupMemoryBarrierWithGroupSync();
    }
    if (lane == 0u) blockSums[group] = blockScan[0];
}

// Single group: exclusive scan of the per-block totals, chunked 64 at a time. The grand total is the
// proxy count, published in the summary entry past the last light.
void scanBlockBases(NeeAtState state, uint lane) {
    DevicePtr<uint> blockSums = (DevicePtr<uint>)neeAtBakePush.blockSumAddress;
    DevicePtr<uint2> distribution = (DevicePtr<uint2>)state.globalDistributionAddress;
    uint blocks = neeAtBlockCount(state);
    if (lane == 0u) blockCarry = 0u;
    GroupMemoryBarrierWithGroupSync();
    for (uint chunk = 0u; chunk < blocks; chunk += 64u) {
        uint index = chunk + lane;
        uint value = index < blocks ? blockSums[index] : 0u;
        uint inclusive = inclusiveBlockScan(lane, value);
        uint chunkBase = blockCarry;
        if (index < blocks) blockSums[index] = chunkBase + inclusive - value;
        GroupMemoryBarrierWithGroupSync();
        if (lane == 0u) blockCarry = chunkBase + blockScan[63];
        GroupMemoryBarrierWithGroupSync();
    }
    if (lane == 0u) distribution[state.lightCount] = uint2(0u, blockCarry);
}

// One thread per light: rescan the block locally and add the block's base to get each light's own
// base offset into the proxy array.
void computeProxyBases(NeeAtState state, uint group, uint lane, uint lightIndex) {
    DevicePtr<uint2> distribution = (DevicePtr<uint2>)state.globalDistributionAddress;
    ConstPtr<uint> blockSums = (ConstPtr<uint>)neeAtBakePush.blockSumAddress;
    uint count = lightIndex < state.lightCount ? distribution[lightIndex].x : 0u;
    uint inclusive = inclusiveBlockScan(lane, count);
    if (lightIndex < state.lightCount) {
        distribution[lightIndex] = uint2(count, blockSums[group] + inclusive - count);
    }
}

// One thread per proxy rather than per light: a light holding most of the distribution would
// otherwise serialize thousands of writes onto a single lane.
void fillProxies(NeeAtState state, uint proxy) {
    ConstPtr<uint2> distribution = (ConstPtr<uint2>)state.globalDistributionAddress;
    uint total = distribution[state.lightCount].y;
    if (proxy >= total) return;
    // Bases strictly increase because every light holds at least one proxy, so the owner is the last
    // light whose base is at or below this proxy.
    uint low = 0u;
    uint high = state.lightCount - 1u;
    while (low < high) {
        uint middle = low + (high - low + 1u) / 2u;
        if (distribution[middle].y <= proxy) low = middle;
        else high = middle - 1u;
    }
    DevicePtr<uint> proxies = (DevicePtr<uint>)state.proxyIndexAddress;
    proxies[proxy] = low;
}

bool reprojectLocalPixel(NeeAtState state, uint2 pixel, float depth, out uint previousIndex) {
    previousIndex = 0u;
    RWTexture2D<float2> currentMotion = ResourceDescriptorHeap[neeAtBakePush.currentMotionIndex];
    ConstPtr<float> previousDepth = (ConstPtr<float>)neeAtBakePush.previousDepthAddress;
    float2 motion = currentMotion[pixel];
    if (!isfinite(depth) || depth <= 0.0 || depth >= 65504.0 || !all(isfinite(motion))) return false;
    float2 previousPixel = floor(float2(pixel) + 0.5 + motion);
    if (any(previousPixel < 0.0)
            || any(previousPixel >= float2(state.extentWidth, state.extentHeight))) return false;
    uint2 previous = uint2(previousPixel);
    float historyDepth = previousDepth[previous.y * state.extentWidth + previous.x];
    // Linear view depths must agree within two percent. Without an expected previous depth this
    // deliberately rejects history during appreciable motion along the view direction.
    if (!isfinite(historyDepth) || historyDepth <= 0.0 || historyDepth >= 65504.0
            || abs(depth - historyDepth) > 0.02 * max(depth, historyDepth)) return false;
    previousIndex = previous.y * state.extentWidth + previous.x;
    return true;
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
    uint2 jitter = neeAtTileJitter(state);
    ConstPtr<uint2> feedback = (ConstPtr<uint2>)neeAtBakePush.previousPixelFeedbackAddress;
    if (lane < state.tileSize * state.tileSize) {
        uint2 local = uint2(lane % state.tileSize, lane / state.tileSize);
        uint2 paddedExtent = uint2(state.tileCountX, state.tileCountY) * state.tileSize;
        uint2 pixel = (tile * state.tileSize + local + paddedExtent - jitter) % paddedExtent;
        uint2 event = uint2(0u);
        if (pixel.x < state.extentWidth && pixel.y < state.extentHeight) {
            RWTexture2D<float> currentDepth = ResourceDescriptorHeap[neeAtBakePush.currentLinearDepthIndex];
            DevicePtr<float> depthHistory = (DevicePtr<float>)neeAtBakePush.currentDepthAddress;
            float depth = currentDepth[pixel];
            // The jittered tile mapping visits every in-extent pixel once, including reset frames.
            depthHistory[pixel.y * state.extentWidth + pixel.x] = depth;
            if ((state.flags & NEE_AT_LOCAL_HISTORY_VALID) != 0u && state.priorLightCount != 0u) {
                uint previousIndex;
                if (reprojectLocalPixel(state, pixel, depth, previousIndex)) event = feedback[previousIndex];
            }
        }
        uint previousLight = event.x & NEE_AT_FEEDBACK_LIGHT_MASK;
        if (asfloat(event.y) > 0.0 && (event.x & NEE_AT_FEEDBACK_COHERENT) != 0u
                && previousLight < state.priorLightCount) {
            uint currentLight = neeAtCurrentLight(previousLight);
            if (currentLight < state.lightCount) {
                uint slot = neeAtHash(currentLight) % state.localSlotCount;
                for (uint probe = 0u; probe < state.localSlotCount; probe++) {
                    uint observed;
                    InterlockedCompareExchange(localLights[slot], 0xffffffffu, currentLight, observed);
                    if (observed == 0xffffffffu || observed == currentLight) {
                        InterlockedAdd(localMasses[slot], 1u);
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

[[vk::push_constant]] public NeeAtBakePush neeAtBakePush;

public void runNeeAtBake(uint groupIndex, uint lane, uint dispatchIndex) {
    NeeAtState state = ((ConstPtr<NeeAtState>)neeAtBakePush.stateAddress)[0];
    if (neeAtBakePush.phase == 0u) {
        collectGlobalFeedback(state, dispatchIndex);
    } else if (neeAtBakePush.phase == 1u) {
        computeProxyCounts(state, groupIndex, lane, dispatchIndex);
    } else if (neeAtBakePush.phase == 2u) {
        if (groupIndex == 0u) scanBlockBases(state, lane);
    } else if (neeAtBakePush.phase == 3u) {
        computeProxyBases(state, groupIndex, lane, dispatchIndex);
    } else if (neeAtBakePush.phase == 4u) {
        fillProxies(state, dispatchIndex);
    } else {
        bakeLocal(state, groupIndex, lane);
    }
}
