// Composites the sRGB-authored, premultiplied UI over the PQ/BT.2020 HDR world while preserving
// Minecraft's sRGB-domain UI blend.
import bindings;

struct PresentPush {
    float uiNits;
};
[[vk::push_constant]] PresentPush pc;

static const float PQ_M1 = 0.1593017578125;
static const float PQ_M2 = 78.84375;
static const float PQ_C1 = 0.8359375;
static const float PQ_C2 = 18.8515625;
static const float PQ_C3 = 18.6875;

float pqEncode(float nits) {
    float y = pow(max(nits, 0.0) / 10000.0, PQ_M1);
    return pow((PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y), PQ_M2);
}

float pqDecode(float e) {
    float ep = pow(max(e, 0.0), 1.0 / PQ_M2);
    float num = max(ep - PQ_C1, 0.0);
    float den = max(PQ_C2 - PQ_C3 * ep, 1.0e-6);
    return 10000.0 * pow(num / den, 1.0 / PQ_M1);
}

float srgbChannelToLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

float3 srgbToLinear(float3 c) {
    return float3(srgbChannelToLinear(c.x), srgbChannelToLinear(c.y), srgbChannelToLinear(c.z));
}

float linearChannelToSrgb(float c) {
    return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055;
}

float3 linearToSrgb(float3 c) {
    return float3(linearChannelToSrgb(c.x), linearChannelToSrgb(c.y), linearChannelToSrgb(c.z));
}

// Row-major mathematical notation; the build targets column-major storage, while mul(M, v) preserves
// the intended transforms.
static const float3x3 BT709_TO_BT2020 = float3x3(
    0.6274039, 0.3292830, 0.0433131,
    0.0690973, 0.9195406, 0.0113612,
    0.0163916, 0.0880132, 0.8955953
);

static const float3x3 BT2020_TO_BT709 = float3x3(
     1.6604910, -0.5876411, -0.0728499,
    -0.1245505,  1.1328999, -0.0083494,
    -0.0181508, -0.1005789,  1.1187297
);

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint width, height;
    outputImage.GetDimensions(width, height);
    if (tid.x >= width || tid.y >= height) return;

    int2 pix = int2(tid.xy);
    float4 ui = sourceImage.Load(int3(pix, 0));
    float alpha = ui.a;
    float3 worldPq = outputImage[pix].rgb;
    if (alpha > 0.0) {
        float3 worldNits2020 = float3(pqDecode(worldPq.x), pqDecode(worldPq.y), pqDecode(worldPq.z));
        float3 worldNits709 = mul(BT2020_TO_BT709, worldNits2020);
        float3 worldSrgb709 = linearToSrgb(worldNits709 / pc.uiNits);

        // The rgba8 overlay already contains the result of Minecraft's code-value-space blending, with
        // premultiplied RGB. Repeating that same over operation here makes a translucent surface retain the
        // same visual opacity in SDR and HDR. Linear-nit blending would make dark panels look washed out.
        float3 compositedSrgb709 = ui.rgb + worldSrgb709 * (1.0 - alpha);
        float3 compositedNits709 = srgbToLinear(compositedSrgb709) * pc.uiNits;
        float3 compositedNits2020 = mul(BT709_TO_BT2020, compositedNits709);
        worldPq = float3(pqEncode(compositedNits2020.x), pqEncode(compositedNits2020.y), pqEncode(compositedNits2020.z));
    }
    outputImage[pix] = float4(worldPq, 1.0);
}
