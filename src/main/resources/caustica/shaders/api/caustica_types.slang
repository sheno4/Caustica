module caustica_types;

// Extension colours and radiance are scene-linear ACEScg. Distances are Minecraft blocks and
// directions are normalized and point away from the shaded position.
public static const uint SURFACE_TWO_SIDED = 0x1u;
public static const uint SURFACE_THIN = 0x2u;

public static const uint EVENT_NONE = 0x0u;
public static const uint EVENT_DIFFUSE = 0x1u;
public static const uint EVENT_GLOSSY = 0x2u;
public static const uint EVENT_SPECULAR = 0x4u;
public static const uint EVENT_REFLECTION = 0x8u;
public static const uint EVENT_TRANSMISSION = 0x10u;

public static const uint DIMENSION_ULTRAWARM = 0x4u;

public struct MaterialInput {
    public float3 baseColor;
    public float opacity;
    public float alphaRoughness;
    public float metalness;
    public float3 specularF0;
    public float3 emissionRadiance;
    public float transmission;
    public float interiorIor;
    public float subsurface;
    public float ambientOcclusion;
    public uint materialTags;
};

public struct SurfaceInput {
    public MaterialInput material;
    public float3 position;
    public float3 geometricNormal;
    public float3 shadingNormal;
    public float3 outgoingDirection;
    public float2 textureCoordinate;
    public float textureFootprint;
    public float biomeTemperature;
    public float biomeHumidity;
    public uint surfaceFlags;
};

public struct SurfaceClosure {
    public float3 shadingNormal;
    public float3 diffuseAlbedo;
    public float3 specularF0;
    public float alphaRoughness;
    public float3 emissionRadiance;
    public float transmission;
    public float interiorIor;
    public float subsurface;
    public uint flags;
};

public struct BsdfQuery {
    public SurfaceClosure surface;
    public float3 outgoingDirection;
    public float3 incomingDirection;
};

public struct BsdfEvaluation {
    public float3 value;
    public float pdf;
    public uint eventFlags;
};

public struct BsdfSampleQuery {
    public SurfaceClosure surface;
    public float3 outgoingDirection;
    public float3 random;
};

public struct BsdfSample {
    public float3 incomingDirection;
    public float3 throughputWeight;
    public float pdf;
    public uint eventFlags;
};

public struct EnvironmentQuery {
    public float3 direction;
    public bool showCelestialBodies;
};
