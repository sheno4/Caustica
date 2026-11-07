import caustica_color;

struct EnvironmentCopyPush {
    uint sourceIndex;
    uint environmentIndex;
    uint celestialIndex;
    uint face;
    uint withCelestials;
};
[[vk::push_constant]] EnvironmentCopyPush pc;
[[vk::binding(0, 0)]] Texture2D<float4> source;
[[vk::binding(1, 0)]] RWTexture2DArray<float4> environment;
[[vk::binding(2, 0)]] RWTexture2DArray<float4> celestial;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    uint width, height, layers;
    environment.GetDimensions(width, height, layers);
    if (id.x >= width || id.y >= height || pc.face >= layers) return;
    uint3 texel = uint3(id.xy, pc.face);
    float3 radiance = bt709ToAcesCg(source.Load(int3(id.xy, 0)).rgb);
    if (pc.withCelestials != 0) {
        // The original compositor can change transmission when it draws a celestial disc. Preserve
        // the signed difference so adding both layers reproduces its full output exactly.
        celestial[texel] = float4(radiance - environment[texel].rgb, 0.0);
    } else {
        environment[texel] = float4(radiance, 1.0);
    }
}
