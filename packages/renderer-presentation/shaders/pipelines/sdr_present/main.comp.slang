// Converts Minecraft's complete sRGB/BT.709 SDR frame to a PQ/BT.2020 image at paper white.
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
    float3 nits709 = srgbToLinear(sourceImage.Load(int3(pix, 0)).rgb) * pc.uiNits;
    float3 nits2020 = mul(BT709_TO_BT2020, nits709);
    float3 pq = float3(pqEncode(nits2020.x), pqEncode(nits2020.y), pqEncode(nits2020.z));
    outputImage[pix] = float4(pq, 1.0);
}
