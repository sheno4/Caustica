import caustica_resources;

struct CompositePush { uint sourceImageIndex; };
[[vk::push_constant]] CompositePush pc;

[shader("fragment")]
float4 main(float4 position : SV_Position) : SV_Target0 {
    Texture2D<float4> sourceImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.sourceImageIndex)];
    return sourceImage[int2(position.xy)];
}
