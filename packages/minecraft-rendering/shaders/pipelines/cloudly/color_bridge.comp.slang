import caustica_color;

struct ColorBridgePush {
    uint sourceIndex;
    uint destinationIndex;
    uint toHost;
    uint padding;
};
[[vk::push_constant]] ColorBridgePush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    Texture2D<float4> source = ResourceDescriptorHeap[pc.sourceIndex];
    RWTexture2D<float4> destination = ResourceDescriptorHeap[pc.destinationIndex];
    uint width, height;
    destination.GetDimensions(width, height);
    if (id.x >= width || id.y >= height) return;
    float4 color = source.Load(int3(id.xy, 0));
    destination[id.xy] = float4(pc.toHost != 0 ? bt709ToAcesCg(color.rgb) : acesCgToBt709(color.rgb), color.a);
}
