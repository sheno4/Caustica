public struct GlowMaskPush {
    public float4x4 curViewProj;
    public float3 camOffset;
    public float4 color;
};

public struct NameTagPush {
    public float4x4 curViewProj;
    public float3 camOffset;
    public uint textureIndex;
    public uint samplerIndex;
};

public struct BlockOutlinePush {
    public float4x4 curViewProj;
    public float4 cameraAndHalfWidthPixels;
    public float4 color;
    public uint tlasIndex;
    public float invViewportWidth;
    public float invViewportHeight;
};
