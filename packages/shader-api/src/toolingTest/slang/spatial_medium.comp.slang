import caustica_volume;
import caustica_types;

public struct Vacuum : IVolumeModel {};
public struct SpatialProbe : IVolumeModel {
    public override SpatialMediumProperties evaluateSpatialMedium(SpatialMediumInput input) {
        SpatialMediumProperties properties;
        properties.extinction = abs(input.position) * input.metersPerSceneUnit;
        properties.scatteringAlbedo = float3(0.8);
        properties.ambientSource = float3(float(input.bindingData & 255u));
        properties.anisotropy = 0.4;
        return properties;
    }
};
struct ProbePush { uint outputIndex; float position; };
[[vk::push_constant]] ProbePush push;
[shader("compute")]
[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    SpatialMediumInput input = {};
    input.position = float3(push.position);
    input.metersPerSceneUnit = 1.0;
    input.bindingData = id.x;
    SpatialProbe probe;
    Vacuum vacuum;
    SpatialMediumProperties medium = probe.evaluateSpatialMedium(input);
    SpatialMediumProperties empty = vacuum.evaluateSpatialMedium(input);
    RWStructuredBuffer<float4> output = ResourceDescriptorHeap[push.outputIndex];
    output[id.x] = float4(medium.extinction + empty.ambientSource, medium.anisotropy);
}
