[[vk::binding(0, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> outputImage;
[[vk::binding(1, 0)]] Sampler2D wideImage;
[[vk::binding(2, 0)]] Sampler2D insetImage;

struct MergePush {
    float centerFraction;
};
[[vk::push_constant]] MergePush pc;

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 thread : SV_DispatchThreadID) {
    uint width, height;
    outputImage.GetDimensions(width, height);
    if (thread.x >= width || thread.y >= height) return;

    float2 uv = (float2(thread.xy) + 0.5) / float2(width, height);
    float2 insetUv = (uv - 0.5) / pc.centerFraction + 0.5;
    float2 edges = min(insetUv, 1.0 - insetUv);
    float weight = smoothstep(0.0, 0.12, min(edges.x, edges.y));

    float4 color = wideImage.SampleLevel(uv, 0.0);
    if (weight > 0.0) {
        color = lerp(color, insetImage.SampleLevel(insetUv, 0.0), weight);
    }
    outputImage[thread.xy] = color;
}
