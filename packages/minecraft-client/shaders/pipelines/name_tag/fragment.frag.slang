import overlay_common;
import caustica_resources;

[[vk::push_constant]] NameTagPush pc;

struct FragmentInput {
    [[vk::location(0)]] float2 uv;
    [[vk::location(1)]] float4 color;
};

[shader("fragment")]
float4 main(FragmentInput input) : SV_Target0 {
    Texture2D<float4> fontAtlas = ResourceDescriptorHeap[NonUniformResourceIndex(pc.textureIndex)];
    SamplerState fontSampler = SamplerDescriptorHeap[NonUniformResourceIndex(pc.samplerIndex)];
    float alpha = fontAtlas.Sample(fontSampler, input.uv).a;
    return float4(input.color.rgb, input.color.a * alpha);
}
