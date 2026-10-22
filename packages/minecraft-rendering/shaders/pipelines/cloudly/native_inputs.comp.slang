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

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    Texture2D<float4> scene = ResourceDescriptorHeap[pc.scene];
    Texture2D<float> depth = ResourceDescriptorHeap[pc.depth];
    RWTexture2D<float4> output = ResourceDescriptorHeap[pc.nativeScene];
    RWTexture2D<float> nativeDepth = ResourceDescriptorHeap[pc.nativeDepth];
    RWTexture2D<float4> velocity = ResourceDescriptorHeap[pc.nativeVelocity];
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
    output[id.xy] = float4(z > 0 ? acesCgToBt709(color.rgb) : float3(0), color.a);
    // The selected native temporal branch derives motion from cloud distance and camera matrices.
    velocity[id.xy] = float4(0);
}
