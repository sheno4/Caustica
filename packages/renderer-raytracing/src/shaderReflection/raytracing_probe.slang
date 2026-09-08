import world_common;
import path_queue_types;
import stable_plane_types;

struct WorldPushLayoutProbe { WorldPush values[2]; };
struct RetainedGeometryRecordLayoutProbe { RetainedGeometryRecord values[2]; };
struct RetainedInstanceRecordLayoutProbe { RetainedInstanceRecord values[2]; };
struct RetainedLightRecordLayoutProbe { RetainedLightRecord values[2]; };
struct NeeAtStateLayoutProbe { NeeAtState values[2]; };
struct PackedPathSegmentLayoutProbe { PackedPathSegment values[2]; };
struct StablePlaneRecordLayoutProbe { StablePlaneRecord values[2]; };

[[vk::binding(0, 0)]] StructuredBuffer<WorldPushLayoutProbe, Std430DataLayout> worldPushLayoutProbe;
[[vk::binding(1, 0)]] StructuredBuffer<RetainedGeometryRecordLayoutProbe, Std430DataLayout> retainedGeometryRecordLayoutProbe;
[[vk::binding(2, 0)]] StructuredBuffer<RetainedLightRecordLayoutProbe, Std430DataLayout> retainedLightRecordLayoutProbe;
[[vk::binding(3, 0)]] StructuredBuffer<NeeAtStateLayoutProbe, Std430DataLayout> neeAtStateLayoutProbe;
[[vk::binding(4, 0)]] StructuredBuffer<PackedPathSegmentLayoutProbe, Std430DataLayout> packedPathSegmentLayoutProbe;
[[vk::binding(5, 0)]] StructuredBuffer<StablePlaneRecordLayoutProbe, Std430DataLayout> stablePlaneRecordLayoutProbe;
[[vk::binding(6, 0)]] StructuredBuffer<RetainedInstanceRecordLayoutProbe, Std430DataLayout> retainedInstanceRecordLayoutProbe;

[shader("compute")]
[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = worldPushLayoutProbe[0].values[0].invViewProj[0][0]
                + float(retainedGeometryRecordLayoutProbe[0].values[0].surfaceImplementation)
                + float(retainedInstanceRecordLayoutProbe[0].values[0].identity)
                + float(retainedLightRecordLayoutProbe[0].values[0].type)
                + float(neeAtStateLayoutProbe[0].values[0].lightCount)
                + float(packedPathSegmentLayoutProbe[0].values[0].flags)
                + float(stablePlaneRecordLayoutProbe[0].values[0].flags);
    }
}
