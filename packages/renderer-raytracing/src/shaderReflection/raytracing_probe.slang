import world_common;
import path_queue_types;
import opacity_micromap_common;

struct WorldPushLayoutProbe { WorldPush values[2]; };
struct RetainedGeometryRecordLayoutProbe { RetainedGeometryRecord values[2]; };
struct RetainedLightRecordLayoutProbe { RetainedLightRecord values[2]; };
struct NeeAtStateLayoutProbe { NeeAtState values[2]; };
struct PackedPathSegmentLayoutProbe { PackedPathSegment values[2]; };

[[vk::binding(0, 0)]] StructuredBuffer<WorldPushLayoutProbe, Std430DataLayout> worldPushLayoutProbe;
[[vk::binding(1, 0)]] StructuredBuffer<RetainedGeometryRecordLayoutProbe, Std430DataLayout> retainedGeometryRecordLayoutProbe;
[[vk::binding(2, 0)]] StructuredBuffer<RetainedLightRecordLayoutProbe, Std430DataLayout> retainedLightRecordLayoutProbe;
[[vk::binding(3, 0)]] StructuredBuffer<NeeAtStateLayoutProbe, Std430DataLayout> neeAtStateLayoutProbe;
[[vk::binding(4, 0)]] StructuredBuffer<PackedPathSegmentLayoutProbe, Std430DataLayout> packedPathSegmentLayoutProbe;

[shader("compute")]
[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = worldPushLayoutProbe[0].values[0].invViewProj[0][0]
                + float(retainedGeometryRecordLayoutProbe[0].values[0].surfaceImplementation)
                + float(retainedLightRecordLayoutProbe[0].values[0].type)
                + float(neeAtStateLayoutProbe[0].values[0].lightCount)
                + float(packedPathSegmentLayoutProbe[0].values[0].nextRecord);
    }
}

[[vk::push_constant]] OpacityMicromapPush opacityMicromapPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeOpacityMicromapPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        uint64_t sink = opacityMicromapPushProbe.primitiveAddress
                + uint64_t(opacityMicromapPushProbe.triangleCount);
    }
}
