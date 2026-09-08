// Scene-referred bloom: a downsample/upsample mip pyramid (Jimenez, "Next Generation Post Processing in
// Call of Duty: Advanced Warfare", SIGGRAPH 2014). One dispatch per pyramid step resolves its images and
// sampler from the renderer-bound heaps. A final MODE_COMPOSITE dispatch adds the finished pyramid onto the
// post chain.
//
// Each level samples only its immediate neighbour, keeping the kernel dense at every scale. Summing
// progressively wider levels produces a monotonic highlight skirt without a hard support edge. The
// Karis average on the first downsample keeps one extreme pixel from dominating the full pyramid.
module caustica_bloom;

import caustica_bloom_types;
import caustica_color;

[[vk::push_constant]] BloomPush pc;

static const float HALF_MAX = 65504.0;
static const float BLOOM_ROLLOFF_START = 25.0;
static const float BLOOM_CONTRIBUTION_CEILING = 50.0;

static const int MODE_PREFILTER = 0;
static const int MODE_DOWNSAMPLE = 1;
static const int MODE_UPSAMPLE = 2;
static const int MODE_COMPOSITE = 3;

float3 tap(float2 uv, float2 offset) {
    Texture2D<float4> source = ResourceDescriptorHeap[NonUniformResourceIndex(pc.sourceImageIndex)];
    SamplerState samplerState = SamplerDescriptorHeap[NonUniformResourceIndex(pc.samplerIndex)];
    return source.SampleLevel(samplerState, uv + offset, 0.0).rgb;
}

float rollOffBloomContribution(float contribution) {
    if (contribution <= BLOOM_ROLLOFF_START) {
        return contribution;
    }

    // Match the linear response and slope at the shoulder, then approach the
    // ceiling asymptotically so exceptionally bright sources cannot dominate.
    float shoulder = BLOOM_CONTRIBUTION_CEILING - BLOOM_ROLLOFF_START;
    return BLOOM_ROLLOFF_START
        + shoulder * (1.0 - exp(-(contribution - BLOOM_ROLLOFF_START) / shoulder));
}

// Quadratic soft-knee threshold (Jimenez): below `threshold - knee` nothing passes, above
// `threshold + knee` the full excess passes, and the transition is C1 so a highlight sliding across the
// threshold does not pop. The result scales the ORIGINAL colour, so hue is untouched.
float3 prefilter(float3 color) {
    color = clamp(color, float3(0.0), float3(HALF_MAX));
    float luminance = dot(color, ACESCG_LUMA);
    float knee = max(pc.softKnee, 1.0e-6);
    float soft = clamp(luminance - pc.threshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee);
    float contribution = max(luminance - pc.threshold, soft);
    contribution = rollOffBloomContribution(contribution);
    return color * (contribution / max(luminance, 1.0e-6));
}

// Karis weight: average in a domain where a single very bright sample cannot outvote its neighbours.
// Applied per 2x2 group (not per tap) so it stays a partial average and does not flatten real highlights.
float karisWeight(float3 color) {
    return 1.0 / (1.0 + dot(color, ACESCG_LUMA));
}

// 13-tap downsample. The five overlapping 2x2 groups (one centred, four at the corners) reconstruct a
// smooth kernel with no sample landing on the destination lattice, which is what keeps the chain free of
// the aliasing pattern a plain 2x2 box downsample produces.
float3 downsample(float2 uv, float2 srcTexel, bool karis) {
    float3 a = tap(uv, float2(-2.0, -2.0) * srcTexel);
    float3 b = tap(uv, float2( 0.0, -2.0) * srcTexel);
    float3 c = tap(uv, float2( 2.0, -2.0) * srcTexel);
    float3 d = tap(uv, float2(-1.0, -1.0) * srcTexel);
    float3 e = tap(uv, float2( 1.0, -1.0) * srcTexel);
    float3 f = tap(uv, float2(-2.0,  0.0) * srcTexel);
    float3 g = tap(uv, float2( 0.0,  0.0) * srcTexel);
    float3 h = tap(uv, float2( 2.0,  0.0) * srcTexel);
    float3 i = tap(uv, float2(-1.0,  1.0) * srcTexel);
    float3 j = tap(uv, float2( 1.0,  1.0) * srcTexel);
    float3 k = tap(uv, float2(-2.0,  2.0) * srcTexel);
    float3 l = tap(uv, float2( 0.0,  2.0) * srcTexel);
    float3 m = tap(uv, float2( 2.0,  2.0) * srcTexel);

    float3 group0 = (d + e + i + j) * 0.25; // centre 2x2
    float3 group1 = (a + b + g + f) * 0.25;
    float3 group2 = (b + c + h + g) * 0.25;
    float3 group3 = (f + g + l + k) * 0.25;
    float3 group4 = (g + h + m + l) * 0.25;

    if (!karis) {
        return group0 * 0.5 + (group1 + group2 + group3 + group4) * 0.125;
    }
    float w0 = 0.5   * karisWeight(group0);
    float w1 = 0.125 * karisWeight(group1);
    float w2 = 0.125 * karisWeight(group2);
    float w3 = 0.125 * karisWeight(group3);
    float w4 = 0.125 * karisWeight(group4);
    float sum = w0 + w1 + w2 + w3 + w4;
    return (group0 * w0 + group1 * w1 + group2 * w2 + group3 * w3 + group4 * w4) / max(sum, 1.0e-6);
}

// 3x3 tent upsample, radius in SOURCE texels. Radius 1 is the standard filter; larger values widen every
// level of the skirt at once, which is the only knob the pyramid needs (the level count sets its reach).
float3 upsample(float2 uv, float2 srcTexel, float radius) {
    float2 o = srcTexel * radius;
    float3 sum = tap(uv, float2(-o.x,  o.y)) + tap(uv, float2(0.0,  o.y)) * 2.0 + tap(uv, float2(o.x,  o.y));
    sum += tap(uv, float2(-o.x, 0.0)) * 2.0 + tap(uv, float2(0.0, 0.0)) * 4.0 + tap(uv, float2(o.x, 0.0)) * 2.0;
    sum += tap(uv, float2(-o.x, -o.y)) + tap(uv, float2(0.0, -o.y)) * 2.0 + tap(uv, float2(o.x, -o.y));
    return sum * (1.0 / 16.0);
}

public void runBloom(uint3 dispatchId) {
    RWTexture2D<float4> dstImage =
            ResourceDescriptorHeap[NonUniformResourceIndex(pc.destinationImageIndex)];
    Texture2D<float4> srcImage =
            ResourceDescriptorHeap[NonUniformResourceIndex(pc.sourceImageIndex)];
    int2 pixel = int2(dispatchId.xy);
    uint width, height;
    dstImage.GetDimensions(width, height);
    if (pixel.x >= int(width) || pixel.y >= int(height)) {
        return;
    }
    uint srcWidth, srcHeight;
    srcImage.GetDimensions(srcWidth, srcHeight);
    float2 srcTexel = 1.0 / float2(float(srcWidth), float(srcHeight));
    float2 uv = (float2(pixel) + 0.5) / float2(float(width), float(height));

    if (pc.mode == MODE_COMPOSITE) {
        // Full-res chain link: the pyramid's base added onto whatever the previous pass left, at the
        // strength this pass alone decides. Bilinear because the pyramid is half-res; 1:1 for the scene,
        // which is why sceneImage can be a storage read rather than another sampler.
        SamplerState samplerState = SamplerDescriptorHeap[NonUniformResourceIndex(pc.samplerIndex)];
        float3 bloom = srcImage.SampleLevel(samplerState, uv, 0.0).rgb * pc.compositeStrength;
        Texture2D<float> exposureImage =
                ResourceDescriptorHeap[NonUniformResourceIndex(pc.exposureImageIndex)];
        float exposure = max(exposureImage.Load(int3(0, 0, 0)), 0.0);
        // The pyramid is exposed; restore the scene's scale before the display pass applies exposure.
        bloom = exposure > 0.0 ? bloom / exposure : float3(0.0);
        Texture2D<float4> sceneImage =
                ResourceDescriptorHeap[NonUniformResourceIndex(pc.sceneImageIndex)];
        dstImage[pixel] = float4(sceneImage.Load(int3(pixel, 0)).rgb + bloom, 1.0);
        return;
    }

    float3 result;
    if (pc.mode == MODE_PREFILTER) {
        // The pyramid works on EXPOSED values so the threshold is a display-referred decision (how far
        // above mid-grey a highlight has to be), not one that drifts with the scene's absolute level.
        Texture2D<float> exposureImage =
                ResourceDescriptorHeap[NonUniformResourceIndex(pc.exposureImageIndex)];
        float exposure = max(exposureImage.Load(int3(0, 0, 0)), 0.0);
        result = prefilter(downsample(uv, srcTexel, true) * exposure);
    } else if (pc.mode == MODE_DOWNSAMPLE) {
        result = downsample(uv, srcTexel, false);
    } else {
        // In-place accumulate: this level already holds its own downsampled band, and the coarser level
        // tented up onto it adds the next-wider band. Summing on the way down the pyramid is what turns
        // the per-level bands into one continuous falloff.
        float3 coarse = upsample(uv, srcTexel, max(pc.radius, 0.0));
        result = dstImage[pixel].rgb + coarse;
    }
    dstImage[pixel] = float4(result, 1.0);
}
