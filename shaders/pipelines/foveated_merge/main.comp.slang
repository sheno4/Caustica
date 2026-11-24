[[vk::binding(0, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> outputImage;
[[vk::binding(1, 0)]] Sampler2D wideImage;
[[vk::binding(2, 0)]] Sampler2D insetImage;

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 thread : SV_DispatchThreadID) {
    uint width, height;
    outputImage.GetDimensions(width, height);
    if (thread.x >= width || thread.y >= height) return;

    uint insetWidth, insetHeight;
    insetImage.GetDimensions(insetWidth, insetHeight);
    float2 extent = float2(width, height);
    float2 insetExtent = float2(insetWidth, insetHeight);
    float2 pixel = float2(thread.xy) + 0.5;
    float2 insetUv = (pixel - 0.5 * (extent - insetExtent)) / insetExtent;
    float2 edges = min(insetUv, 1.0 - insetUv);
    float weight = smoothstep(0.0, 0.12, min(edges.x, edges.y));

    float4 color = wideImage.SampleLevel(pixel / extent, 0.0);
    if (weight > 0.0) {
        color = lerp(color, insetImage.SampleLevel(insetUv, 0.0), weight);
    }
    outputImage[thread.xy] = color;
}
