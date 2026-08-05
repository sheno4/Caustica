// Build-time reflection probe only. This file is not packaged as a runtime shader.
// Exposing WorldPush as a structured-buffer element makes Slang's JSON reflection include the
// complete Std430DataLayout: field types, byte offsets, total size, matrix mode, and array counts.
import world_common;
import display_common;
import caustica_bloom_types;
import sky_lut_types;

struct WorldPushLayoutProbe {
    WorldPush values[2]; // array stride is the complete, tail-padded WorldPush byte size
};

struct MaterialHeaderLayoutProbe {
    MaterialHeader values[2]; // array stride is the complete, tail-padded MaterialHeader byte size
};

struct ExposureStateLayoutProbe {
    ExposureState values[2]; // array stride is the complete, tail-padded ExposureState byte size
};

[[vk::binding(0, 0)]] StructuredBuffer<WorldPushLayoutProbe, Std430DataLayout> worldPushLayoutProbe;
[[vk::binding(1, 0)]] StructuredBuffer<MaterialHeaderLayoutProbe, Std430DataLayout> materialHeaderLayoutProbe;
[[vk::binding(2, 0)]] StructuredBuffer<ExposureStateLayoutProbe, Std430DataLayout> exposureStateLayoutProbe;
[[vk::push_constant]] WorldPushConstants pushConstantsLayoutProbe;

[shader("compute")]
[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    // Keep the parameter reachable without requiring a writable output resource.
    if (id.x == 0xFFFFFFFFu) {
        float sink = worldPushLayoutProbe[0].values[0].invViewProj[0][0]
                + materialHeaderLayoutProbe[0].values[0].params.x
                + exposureStateLayoutProbe[0].values[0].previous
                + float(pushConstantsLayoutProbe.frameIndex);
    }
}

// Each display-chain push-constant struct gets its own tiny entry point below: SPIR-V permits only one
// push-constant block reachable per entry point, so each entry point touches exactly one. They are
// still reported in the same reflection-json pass because
// reflection parameters are enumerated module-wide, not per-entry-point -- see the main() probe
// above for the equivalent structured-buffer case.

[[vk::push_constant]] ExposureHistPush exposureHistPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeExposureHistPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = float(exposureHistPushProbe.stride);
    }
}

[[vk::push_constant]] ExposureResolvePush exposureResolvePushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeExposureResolvePush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = exposureResolvePushProbe.key + exposureResolvePushProbe.preExposure;
    }
}

[[vk::push_constant]] DisplayPush displayPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeDisplayPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = float(displayPushProbe.hdrEnabled) + displayPushProbe.hdrPeakNits;
    }
}

[[vk::push_constant]] DebugPresentPush debugPresentPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeDebugPresentPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = float(debugPresentPushProbe.debugView) + debugPresentPushProbe.centerWeightFloor;
    }
}

[[vk::push_constant]] SkyLutPush skyLutPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeSkyLutPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = skyLutPushProbe.celestial.x + skyLutPushProbe.skyLook3.x;
    }
}

[[vk::push_constant]] BloomPush bloomPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeBloomPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = float(bloomPushProbe.mode) + bloomPushProbe.radius;
    }
}
