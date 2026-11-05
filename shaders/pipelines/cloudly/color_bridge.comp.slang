import caustica_color;

struct ColorBridgePush {
    uint sourceIndex;
    uint destinationIndex;
    uint toHost;
    uint padding;
};
[[vk::push_constant]] ColorBridgePush pc;
[[vk::binding(0, 0)]] Texture2D<float4> source;
[[vk::binding(1, 0)]] RWTexture2D<float4> destination;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    uint width, height;
    destination.GetDimensions(width, height);
    if (id.x >= width || id.y >= height) return;
    float4 color = source.Load(int3(id.xy, 0));
    destination[id.xy] = float4(pc.toHost != 0 ? exposedRadianceToDisplay(color.rgb) : displayToExposedRadiance(color.rgb), color.a);
}
