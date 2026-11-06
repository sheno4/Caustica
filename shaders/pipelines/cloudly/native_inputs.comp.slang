import caustica_color;

struct NativeInputs {
    uint scene;
    uint depth;
    uint nativeScene;
    uint nativeDepth;
    uint nativeVelocity;
    uint padding;
    float2 traceJitter;
};
[[vk::push_constant]] NativeInputs pc;
[[vk::binding(0, 0)]] Texture2D<float4> scene;
[[vk::binding(1, 0)]] Texture2D<float> depth;
[[vk::binding(2, 0)]] RWTexture2D<float4> output;
[[vk::binding(3, 0)]] RWTexture2D<float> nativeDepth;
[[vk::binding(4, 0)]] RWTexture2D<float4> velocity;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    uint width, height, dw, dh;
    output.GetDimensions(width, height);
    depth.GetDimensions(dw, dh);
    if (id.x >= width || id.y >= height) return;
    float2 uv = (float2(id.xy) + 0.5) / float2(width, height);
    int2 sample = clamp(int2(floor(uv * float2(dw, dh) - pc.traceJitter)), int2(0), int2(dw, dh)-1);
    float z = depth.Load(int3(sample, 0));
    nativeDepth[id.xy] = z;
    float4 color = scene.Load(int3(id.xy, 0));
    // The native atmosphere supplies the environment; world surface radiance remains intact.
    float3 radiance = pc.padding != 0 ? color.rgb : displayToExposedRadiance(color.rgb);
    output[id.xy] = float4(z > 0 ? radiance : float3(0), color.a);
    // The selected native temporal branch derives motion from cloud distance and camera matrices.
    velocity[id.xy] = float4(0);
}
