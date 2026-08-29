import caustica_resources;

struct PostPush {
    SampledTexture2DIndex input;
    SampledTexture2DIndex exposure;
    uint outputIndex;
};

[[vk::push_constant]] PostPush pushData;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadID : SV_DispatchThreadID) {
    Texture2D<float4> input = sampledTexture2D(pushData.input);
    RWTexture2D<float4> output = ResourceDescriptorHeap[NonUniformResourceIndex(pushData.outputIndex)];
    output[dispatchThreadID.xy] = input.Load(int3(dispatchThreadID.xy, 0));
}
