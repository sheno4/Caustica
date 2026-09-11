// Strided log2-luminance histogram over the post-RR trace color. Metering after RR avoids the
// Jensen bias that Monte-Carlo noise introduces into the logarithmic average.

import display_common;

[[vk::push_constant]] ExposureHistPush pc;

static const float LOG_MIN = -12.0;
static const float LOG_MAX = 12.0;
static const float INV_LOG_RANGE = 1.0 / (LOG_MAX - LOG_MIN);

// One 16x16 workgroup == 256 threads == one thread per luminance bin. Each thread initializes and
// flushes all three population bins. Per-pixel atomics stay in shared memory; only non-empty bins
// pay global atomics.
groupshared uint localBins[768];

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 dispatchId : SV_DispatchThreadID, uint groupIndex : SV_GroupIndex) {
    [format("rgba16f")] RWTexture2D<float4> colorImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.colorImageIndex)];
    [format("r32f")] RWTexture2D<float> depthImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.depthImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> albedoImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.albedoImageIndex)];
    DevicePtr<uint> histBins = DevicePtr<uint>(pc.histogramAddress);
    [unroll]
    for (uint offset = 0u; offset < 768u; offset += 256u) {
        localBins[offset + groupIndex] = 0u;
    }
    GroupMemoryBarrierWithGroupSync();

    int2 pix = int2(dispatchId.xy * pc.stride);
    uint w, h;
    colorImage.GetDimensions(w, h);
    if (pix.x < int(w) && pix.y < int(h)) {
        float3 rgb = colorImage[pix].rgb;
        // Invalid radiance is not a meaningful metering sample. Skipping it is safe because resolve
        // derives its population from the bins instead of assuming image area.
        if (all(isfinite(rgb))) {
            rgb = max(rgb, float3(0.0));
            // The reconstructed scene image is linear ACEScg; meter it in the transport basis.
            float lum = dot(rgb, ACESCG_LUMA);
            float logLum = clamp(log2(max(lum, 1.0e-5)), LOG_MIN, LOG_MAX);
            uint bin = min(uint(floor((logLum - LOG_MIN) * INV_LOG_RANGE * 256.0)), 255u);

            float2 uv = (float2(pix) + 0.5) / float2(w, h);
            float weight = exposureCenterWeight(uv, pc.centerWeightSigma, pc.centerWeightFloor);
            uint fixedWeight = uint(round(weight * 256.0));

            uint depthW, depthH;
            depthImage.GetDimensions(depthW, depthH);
            int2 guidePix = min(int2(uv * float2(depthW, depthH)), int2(depthW, depthH) - 1);
            bool environment = depthImage[guidePix] <= 1.0e-6;
            bool emissive = !environment && albedoImage[guidePix].a > 0.5;
            uint populationOffset = environment ? 256u : (emissive ? 512u : 0u);
            uint weightedBin = bin + populationOffset;
            if (fixedWeight > 0u) {
                uint unused;
                InterlockedAdd(localBins[weightedBin], fixedWeight, unused);
            }
        }
    }

    GroupMemoryBarrierWithGroupSync();
    [unroll]
    for (uint offset = 0u; offset < 768u; offset += 256u) {
        uint bin = offset + groupIndex;
        uint count = localBins[bin];
        if (count > 0u) {
            uint unused;
            InterlockedAdd(histBins[bin], count, unused);
        }
    }
}
