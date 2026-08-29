import overlay_common;

[[vk::push_constant]] BlockOutlinePush pc;

struct VertexInput {
    [[vk::location(0)]] float3 start;
    [[vk::location(1)]] float3 end;
};

struct VertexOutput {
    float4 position : SV_Position;
    [[vk::location(0)]] float3 cameraRelative;
};

bool clipPlane(inout float4 aClip, inout float4 bClip,
        inout float3 aRelative, inout float3 bRelative, float aDistance, float bDistance) {
    if (aDistance < 0.0 && bDistance < 0.0) return false;
    if (aDistance < 0.0 || bDistance < 0.0) {
        float t = aDistance / (aDistance - bDistance);
        float4 clipped = lerp(aClip, bClip, t);
        float3 relative = lerp(aRelative, bRelative, t);
        if (aDistance < 0.0) {
            aClip = clipped;
            aRelative = relative;
        } else {
            bClip = clipped;
            bRelative = relative;
        }
    }
    return true;
}

[shader("vertex")]
VertexOutput main(VertexInput input, uint vertexIndex : SV_VertexID) {
    VertexOutput output;
    float3 camera = pc.cameraAndHalfWidthPixels.xyz;
    float3 aRelative = input.start - camera;
    float3 bRelative = input.end - camera;
    float4 aClip = mul(pc.curViewProj, float4(aRelative, 1.0));
    float4 bClip = mul(pc.curViewProj, float4(bRelative, 1.0));
    const float epsilon = 1.0e-5;
    bool visible = clipPlane(aClip, bClip, aRelative, bRelative,
            aClip.w - epsilon, bClip.w - epsilon);
    if (visible) {
        visible = clipPlane(aClip, bClip, aRelative, bRelative,
                aClip.z - epsilon * aClip.w, bClip.z - epsilon * bClip.w);
    }
    if (!visible) {
        output.position = float4(0.0, 0.0, -1.0, 1.0);
        output.cameraRelative = float3(0.0);
        return output;
    }

    float2 aNdc = aClip.xy / aClip.w;
    float2 bNdc = bClip.xy / bClip.w;
    float2 invViewport = float2(pc.invViewportWidth, pc.invViewportHeight);
    float2 deltaPixels = (bNdc - aNdc) / (2.0 * invViewport);
    float lengthSquared = dot(deltaPixels, deltaPixels);
    float2 perpendicular = lengthSquared > epsilon
            ? float2(-deltaPixels.y, deltaPixels.x) * rsqrt(lengthSquared) : float2(0.0);

    uint corner = vertexIndex % 6u;
    bool useEnd = corner == 2u || corner == 3u || corner == 4u;
    float side = corner == 0u || corner == 4u || corner == 5u ? -1.0 : 1.0;
    output.position = useEnd ? bClip : aClip;
    output.cameraRelative = useEnd ? bRelative : aRelative;
    output.position.xy += side * perpendicular * pc.cameraAndHalfWidthPixels.w
            * 2.0 * invViewport * output.position.w;
    return output;
}
