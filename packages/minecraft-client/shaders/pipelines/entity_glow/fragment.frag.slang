import overlay_common;

[[vk::push_constant]] GlowMaskPush pc;

[shader("fragment")]
float4 main() : SV_Target0 {
    return pc.color;
}
