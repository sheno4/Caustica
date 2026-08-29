import caustica_resources;

struct UiPush {
    AccelerationStructureIndex scene;
    uint outputIndex;
};

[[vk::push_constant]] UiPush pushData;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadID : SV_DispatchThreadID) {
    RaytracingAccelerationStructure scene = accelerationStructure(pushData.scene);
    RWTexture2D<float4> output = ResourceDescriptorHeap[NonUniformResourceIndex(pushData.outputIndex)];
    output[dispatchThreadID.xy] = float4(1.0, 0.5, 0.1, 0.75);
}
