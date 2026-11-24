import foveation;

[[vk::binding(0, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> outputImage;
[[vk::binding(1, 0)]] Sampler2D wideImage;
[[vk::binding(2, 0)]] Sampler2D insetImage;

struct MergePush {
    float4x4 eyeToInset;
};
[[vk::push_constant]] MergePush pc;

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 thread : SV_DispatchThreadID) {
    uint width, height;
    outputImage.GetDimensions(width, height);
    if (thread.x >= width || thread.y >= height) return;

    float2 uv = (float2(thread.xy) + 0.5) / float2(width, height);
    float2 insetUv = projectInsetUv(uv, pc.eyeToInset);
    float edge = insetEdgeDistance(insetUv);
    float4 color;
    if (edge >= INSET_BLEND_WIDTH) {
        color = insetImage.SampleLevel(insetUv, 0.0);
    } else {
        color = wideImage.SampleLevel(uv, 0.0);
        if (edge > 0.0) {
            color = lerp(color, insetImage.SampleLevel(insetUv, 0.0), smoothstep(0.0, INSET_BLEND_WIDTH, edge));
        }
    }
    outputImage[thread.xy] = color;
}
