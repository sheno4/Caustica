import caustica_resources;

struct ProbeParameters {
    SampledTexture2DIndex textureIndex;
    SamplerIndex samplerIndex;
    uint outputBufferIndex;
};

[[vk::push_constant]] ConstantBuffer<ProbeParameters> parameters;

[shader("compute")]
[numthreads(1, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    RWStructuredBuffer<float4> output =
            ResourceDescriptorHeap[NonUniformResourceIndex(parameters.outputBufferIndex)];
    output[dispatchThreadId.x] = sampleTexture2DLod(
            parameters.textureIndex, parameters.samplerIndex, float2(0.5), 0.0);
}
