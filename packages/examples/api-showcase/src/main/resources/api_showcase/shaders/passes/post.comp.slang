import caustica_resources;
import showcase_pass_types;

[[vk::push_constant]] ShowcasePostPush pushData;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadID : SV_DispatchThreadID) {
    if (any(dispatchThreadID.xy >= uint2(pushData.width, pushData.height))) return;
    Texture2D<float4> input = ResourceDescriptorHeap[NonUniformResourceIndex(pushData.inputImageIndex)];
    Texture2D<float> exposure = ResourceDescriptorHeap[NonUniformResourceIndex(pushData.exposureImageIndex)];
    RWTexture2D<float4> output = ResourceDescriptorHeap[NonUniformResourceIndex(pushData.outputImageIndex)];
    float4 source = input.Load(int3(dispatchThreadID.xy, 0));
    output[dispatchThreadID.xy] = lerp(source, source * exposure.Load(int3(0, 0, 0)), pushData.strength);
}
