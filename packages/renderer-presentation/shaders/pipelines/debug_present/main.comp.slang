// Computes and presents debugView content as a final inspection pass after ordinary path-traced
// radiance, RR/fallback upscale, exposure metering, and the ACES display transform. Keeping debug
// coloring out of the occupancy-bound primary raygen avoids register pressure on the normal render path.
//
// Reads the guide buffers directly at their real render resolution and nearest-samples them into the
// display-resolution output. Debug mode deliberately leaves DLSS-RR and its jitter enabled, so this
// pass shows the actual guide inputs used by RR rather than changing the renderer being inspected.

import display_common;
import presentation_common;

[[vk::push_constant]] DebugPresentPush pc;

// OCIO cg-config-v4.0.0 ACES 2.0: ACEScg/AP1/D60 to Linear Rec.709/D65. Debug guide colors cross
// back to the rgba8 target here; semantic debug colors (normal, depth, motion, weight) are already
// authored as literal sRGB code values and bypass this conversion.
static const float3 ACESCG_TO_BT709_R = float3( 1.70505095, -0.62179214, -0.08325887);
static const float3 ACESCG_TO_BT709_G = float3(-0.13025641,  1.14080477, -0.01054832);
static const float3 ACESCG_TO_BT709_B = float3(-0.02400336, -0.12896897,  1.15297234);

float3 acesCgToSrgb(float3 color) {
    float3 linearBt709 = float3(
            dot(color, ACESCG_TO_BT709_R),
            dot(color, ACESCG_TO_BT709_G),
            dot(color, ACESCG_TO_BT709_B));
    return linearToSrgb(clamp(linearBt709, 0.0, 1.0));
}

// Display-referred, deliberately discrete one-stop bands. `ev == 0` means the value entering ACES
// is 18% grey; cool colors are below it, warm colors above it. These values are literal SDR/sRGB
// debug colors written after the display transform, not scene radiance.
float3 exposureFalseColor(float ev) {
    float band = floor(ev + 0.5);
    if (band <= -6.0) return float3(0.01, 0.00, 0.03);
    if (band == -5.0) return float3(0.12, 0.00, 0.25);
    if (band == -4.0) return float3(0.10, 0.15, 0.75);
    if (band == -3.0) return float3(0.00, 0.55, 1.00);
    if (band == -2.0) return float3(0.00, 0.75, 0.55);
    if (band == -1.0) return float3(0.10, 0.80, 0.15);
    if (band ==  0.0) return float3(0.46, 0.46, 0.46);
    if (band ==  1.0) return float3(0.78, 0.78, 0.05);
    if (band ==  2.0) return float3(1.00, 0.50, 0.00);
    if (band ==  3.0) return float3(1.00, 0.05, 0.02);
    if (band ==  4.0) return float3(1.00, 0.00, 0.70);
    if (band ==  5.0) return float3(1.00, 0.55, 0.80);
    return float3(1.00, 1.00, 1.00);
}

// Histogram metering weight before fixed-point quantization, including resolve's population scales.
float meteringWeight(float2 uv, float depth, bool emissive) {
    float centerWeight = exposureCenterWeight(uv, pc.centerWeightSigma, pc.centerWeightFloor);
    DevicePtr<ExposureState> exposureState = DevicePtr<ExposureState>(pc.exposureStateAddress);
    float populationWeight = depth <= 1.0e-6 ? exposureState[0].meteringSkyScale
            : (emissive ? exposureState[0].meteringEmissiveScale : 1.0);
    return centerWeight * populationWeight;
}

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 dispatchId : SV_DispatchThreadID) {
    [format("rgba8")] RWTexture2D<float4> outputImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.outputImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> gNormal = ResourceDescriptorHeap[NonUniformResourceIndex(pc.normalImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> gAlbedo = ResourceDescriptorHeap[NonUniformResourceIndex(pc.albedoImageIndex)];
    [format("r32f")] RWTexture2D<float> gDepth = ResourceDescriptorHeap[NonUniformResourceIndex(pc.depthImageIndex)];
    [format("rg16f")] RWTexture2D<float2> gMotion = ResourceDescriptorHeap[NonUniformResourceIndex(pc.motionImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> gSpecAlbedo = ResourceDescriptorHeap[NonUniformResourceIndex(pc.specAlbedoImageIndex)];
    [format("rg16f")] RWTexture2D<float2> gSpecMotion = ResourceDescriptorHeap[NonUniformResourceIndex(pc.specMotionImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> sceneImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.sceneImageIndex)];
    [format("r32f")] RWTexture2D<float> exposureImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.exposureImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> traceRadiance = ResourceDescriptorHeap[NonUniformResourceIndex(pc.traceRadianceImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> stablePlaneMetadata = ResourceDescriptorHeap[NonUniformResourceIndex(pc.stablePlaneMetadataImageIndex)];
    int2 pix = int2(dispatchId.xy);
    uint w, h;
    outputImage.GetDimensions(w, h);
    if (pix.x >= int(w) || pix.y >= int(h)) {
        return;
    }

    uint guideW, guideH;
    gNormal.GetDimensions(guideW, guideH);
    float2 uv = (float2(pix) + 0.5) / float2(w, h);
    int2 guidePix = min(int2(uv * float2(guideW, guideH)), int2(guideW, guideH) - 1);
    float2 motionToDisplay = float2(w, h) / float2(guideW, guideH);

    float4 normalRough = gNormal[guidePix];
    float3 dbg;
    switch (pc.debugView) {
    case 1u: { // Normals
        dbg = normalRough.xyz * 0.5 + 0.5;
        break;
    }
    case 2u: { // Diffuse albedo
        dbg = acesCgToSrgb(gAlbedo[guidePix].rgb);
        break;
    }
    case 3u: { // Depth
        float depth = gDepth[guidePix];
        dbg = float3(depth, depth, depth);
        break;
    }
    case 4u: { // Roughness
        dbg = float3(normalRough.w, normalRough.w, normalRough.w);
        break;
    }
    case 6u: { // Specular albedo
        dbg = acesCgToSrgb(gSpecAlbedo[guidePix].rgb);
        break;
    }
    case 7u: { // Specular motion
        dbg = float3(clamp(0.5 + gSpecMotion[guidePix] * motionToDisplay * 0.05, 0.0, 1.0), 0.5);
        break;
    }
    case 8u: { // Exposure false color
        float luminance = dot(max(sceneImage[pix].rgb, float3(0.0)), ACESCG_LUMA);
        float exposedLuminance = luminance * max(exposureImage[int2(0, 0)], 0.0);
        float evFromMidGrey = log2(max(exposedLuminance, 1.0e-6) / 0.18);
        dbg = exposureFalseColor(evFromMidGrey);
        break;
    }
    case 9u: { // Metering weight
        float weight = meteringWeight(uv, gDepth[guidePix], gAlbedo[guidePix].a > 0.5);
        dbg = float3(weight, weight, weight);
        break;
    }
    case 10u: { // Stable-plane validity
        // X stores the available plane count divided by three; one plane is already present.
        float planeShare = stablePlaneMetadata[guidePix].x;
        dbg = planeShare > 0.0 ? float3(0.1, 0.9, 0.2) : float3(0.9, 0.05, 0.05);
        break;
    }
    case 11u: { // Stable-plane path type
        float4 metadata = stablePlaneMetadata[guidePix];
        bool reflection = metadata.z > 0.0;
        bool transmission = metadata.w > 0.5;
        dbg = metadata.x <= 0.0 ? float3(0.0)
                : reflection && transmission ? float3(0.8, 0.2, 0.95)
                : transmission ? float3(0.1, 0.8, 0.9)
                : reflection ? float3(0.15, 0.35, 1.0) : float3(0.7);
        break;
    }
    case 12u: { // Mapped primary depth
        float primaryDepth = max(gDepth[guidePix], 0.0);
        float mapped = 1.0 - exp(-primaryDepth * 0.05);
        dbg = float3(mapped, mapped, mapped);
        break;
    }
    case 14u: { // Trace radiance
        float3 radiance = traceRadiance[guidePix].rgb;
        float luminance = dot(max(radiance, float3(0.0)), ACESCG_LUMA);
        dbg = acesCgToSrgb(radiance / (1.0 + luminance));
        break;
    }
    case 15u: { // Stable-plane metadata
        float4 metadata = stablePlaneMetadata[guidePix];
        dbg = metadata.x > 0.0 ? float3(metadata.z, metadata.w * metadata.y, metadata.y)
                : float3(0.0);
        break;
    }
    case 5u: // Motion
    case 13u: // Primary motion uses the same guide
    default: {
        dbg = float3(clamp(0.5 + gMotion[guidePix] * motionToDisplay * 0.05, 0.0, 1.0), 0.5);
        break;
    }
    }
    outputImage[pix] = float4(clamp(dbg, 0.0, 1.0), 1.0);
}
