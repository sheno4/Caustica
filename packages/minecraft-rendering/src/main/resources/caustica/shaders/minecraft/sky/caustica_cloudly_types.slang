module caustica_cloudly_types;

/** Absolute source components adapted to host axes (X,Z,-Y), with source UV axes retained. */
public struct CloudlyCloudComponent {
    public float4 centerAndDensity;
    /** XYZ are reciprocal full source dimensions; W is the cached cosine of source Z rotation. */
    public float4 inverseSizeAndCos;
    /** XYZ retain source UV scale; W caches the source rotation's sine. */
    public float4 uvScale;
    public float4 uvOffset;
    public uint textureIndex;
    public uint mipLevels;
    public uint layerIndex;
    public uint padding1;
    /** Immutable source dimensions avoid a texture metadata query at every march and shadow step. */
    public float4 textureDimensions;
};

/** Authored world-cell sizes and fractal parameters under the declared procedural-detail approximation. */
public struct CloudlyNoiseProfile {
    public float4 cellAndOctaves;
    public float4 fractal;
    public float4 remap;
    public float4 displacement;
};

/** Physical lengths and extinction use metres under the pack's explicit approximation adapter. */
public struct CloudlyCloudPush {
    public uint64_t componentsAddress;
    public uint componentCount;
    public uint samplerIndex;
    public uint destinationIndex;
    public uint sceneIndex;
    public uint depthIndex;
    public uint steps;
    public float4 clipX;
    public float4 clipY;
    public float4 clipZ;
    public float4 clipW;
    public float4 cameraAndUnits;
    public float4 sunDirectionAndIlluminance;
    public float4 opticalParameters;
    public float4 lightingParameters;
    public float4 traceParameters;
    public float4 shadowParameters;
    public float4 sunColor;
    public float4 ambientColor;
    public float4 multipleScattering;
    public float4 detailParameters;
};
