module sky_lut_types;

import world_common;

public struct SkyLutPush {
    public float4 celestial;
    public float4 skyLook0;
    public float4 skyLook1;
    public float4 skyLook2;
    public float4 skyLook3;
};

public WorldPush skyLutWorldPush(SkyLutPush pc) {
    WorldPush push = {};
    push.celestial = pc.celestial;
    push.skyLook0 = pc.skyLook0;
    push.skyLook1 = pc.skyLook1;
    push.skyLook2 = pc.skyLook2;
    push.skyLook3 = pc.skyLook3;
    return push;
}
