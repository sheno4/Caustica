// Composites the sRGB-authored, premultiplied UI over the PQ/BT.2020 HDR world while preserving
// Minecraft's sRGB-domain UI blend.
import presentation_common;
[[vk::push_constant]] PresentPush pc;

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    [format("rgba16f")] RWTexture2D<float4> outputImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.outputImageIndex)];
    Texture2D<float4> sourceImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.sourceImageIndex)];
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
