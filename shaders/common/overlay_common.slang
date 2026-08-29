public struct OverlayPush {
    public float4x4 curViewProj;
    public float3 camOffset;
    public float4 color;
    public uint tlasIndex;
};

public struct NameTagPush {
    public float4x4 curViewProj;
    public float3 camOffset;
    public uint textureIndex;
    public uint samplerIndex;
};
