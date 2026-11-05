import world_common;
import bindings;

/** Native cloud visibility at a rebased host position, for the light that generated the field. */
public float3 cloudlyDistantTransmittance(WorldPush push, float3 position, float3 direction) {
    if (push.cloudlyShadowOrigin.w == 0.0
            || dot(direction, push.cloudlyShadowLight.xyz) < 0.999) {
        return float3(1.0);
    }
    float3 cameraRelative = position - push.camOffset;
    float3 sourceOffset = float3(cameraRelative.x, -cameraRelative.z, cameraRelative.y);
    float3 uv = push.cloudlyShadowOrigin.xyz + sourceOffset * push.cloudlyShadowScale.xyz;
    if (any(uv < 0.0) || any(uv > 1.0)) {
        return float3(1.0);
    }
    return saturate(cloudSurfaceTransmittanceTex.SampleLevel(uv, 0.0).rgb);
}
