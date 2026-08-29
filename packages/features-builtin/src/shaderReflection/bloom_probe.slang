import caustica_bloom_types;

[[vk::push_constant]] BloomPush bloomPushProbe;

[shader("compute")]
[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = float(bloomPushProbe.destinationImageIndex)
                + float(bloomPushProbe.samplerIndex)
                + float(bloomPushProbe.mode)
                + bloomPushProbe.radius;
    }
}
