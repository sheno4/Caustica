import showcase_pass_types;

[[vk::push_constant]] ShowcasePostPush postPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probePostPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = float(postPushProbe.inputImageIndex + postPushProbe.width) + postPushProbe.strength;
    }
}

[[vk::push_constant]] ShowcaseUiPush uiPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeUiPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        uint sink = uiPushProbe.sceneAccelerationStructureIndex;
    }
}
