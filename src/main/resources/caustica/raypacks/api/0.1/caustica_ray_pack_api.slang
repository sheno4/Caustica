module caustica_ray_pack_api;

// Ray-pack colours and radiance are scene-linear ACEScg. Distances are Minecraft blocks, treated as
// metres by the default pack. Directions are normalized and point away from the shaded position.

public static const uint PACK_SURFACE_TWO_SIDED = 0x1u;
public static const uint PACK_SURFACE_THIN = 0x2u;

public static const uint PACK_EVENT_NONE = 0x0u;
public static const uint PACK_EVENT_DIFFUSE = 0x1u;
public static const uint PACK_EVENT_GLOSSY = 0x2u;
public static const uint PACK_EVENT_SPECULAR = 0x4u;
public static const uint PACK_EVENT_REFLECTION = 0x8u;
public static const uint PACK_EVENT_TRANSMISSION = 0x10u;

public static const uint PACK_DIMENSION_HAS_SKY = 0x1u;
public static const uint PACK_DIMENSION_HAS_WEATHER = 0x2u;
public static const uint PACK_DIMENSION_ULTRAWARM = 0x4u;

public static const uint PACK_MEDIUM_AIR = 0u;
public static const uint PACK_MEDIUM_WATER = 1u;

public struct PackFrameContext {
    public float timeSeconds;
    public uint frameIndex;
    public float3 cameraPosition;
    public float rain;
    public float thunder;
    public float dayFraction;
    public float nightFraction;
    public float3 sunDirection;
    public float3 moonDirection;
    public uint dimensionFlags;
};

// These are decoded material facts, not GPU texture or material-table layouts. The engine may change
// storage and texture paging without changing this contract.
public struct PackMaterialInput {
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

public struct PackSurfaceInput {
    public PackFrameContext frame;
    public PackMaterialInput material;
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

public struct PackSurfaceClosure {
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

public struct PackBsdfQuery {
    public PackSurfaceClosure surface;
    public float3 outgoingDirection;
    public float3 incomingDirection;
    public float incidentIor;
    public float transmittedIor;
};

public struct PackBsdfEvaluation {
    // value is the BSDF without the cosine term. The engine applies value * abs(N.L) / pdf.
    public float3 value;
    public float pdf;
    public uint eventFlags;
};

public struct PackBsdfSampleQuery {
    public PackSurfaceClosure surface;
    public float3 outgoingDirection;
    public float incidentIor;
    public float transmittedIor;
    // The engine supplies independent uniform variates so replay and scheduling remain engine-owned.
    public float3 random;
};

public struct PackBsdfSample {
    public float3 incomingDirection;
    // The engine multiplies path throughput by this value directly.
    public float3 throughputWeight;
    public float pdf;
    public uint eventFlags;
};

public struct PackEnvironmentQuery {
    public PackFrameContext frame;
    public float3 direction;
    public bool showCelestialBodies;
};

public struct PackDirectionalLight {
    // direction points from a shaded position toward the light.
    public float3 direction;
    public float3 irradiance;
    public float angularRadius;
};

public struct PackMediumInput {
    public PackFrameContext frame;
    public float3 position;
    public float3 tint;
    public float density;
    public float biomeTemperature;
    public float biomeHumidity;
    public uint mediumTag;
};

public struct PackMedium {
    public float3 scattering;
    public float3 absorption;
    public float3 emissionRadiance;
    public float anisotropy;
    public float ior;
};

public struct PackPhaseQuery {
    public PackMedium medium;
    public float3 incomingDirection;
    public float3 outgoingDirection;
};

public struct PackPhaseEvaluation {
    public float value;
    public float pdf;
};

public struct PackPhaseSampleQuery {
    public PackMedium medium;
    public float3 incomingDirection;
    public float2 random;
};

public struct PackPhaseSample {
    public float3 outgoingDirection;
    public float throughputWeight;
    public float pdf;
};

// The engine specializes its shaders against one implementation of this interface. Only appearance
// math crosses this boundary; traversal, resource binding, and light-candidate selection stay engine-owned.
public interface IPackSurfaceModel {
    public float evaluateCoverage(PackSurfaceInput input);
    public PackSurfaceClosure createSurface(PackSurfaceInput input);
    public PackBsdfEvaluation evaluateBsdf(PackBsdfQuery query);
    public PackBsdfSample sampleBsdf(PackBsdfSampleQuery query);
};

public interface IPackEnvironmentModel {
    public float3 evaluateEnvironment(PackEnvironmentQuery query);
    public uint getDirectionalLightCount(PackFrameContext frame);
    public PackDirectionalLight getDirectionalLight(PackFrameContext frame, uint index);
};

public interface IPackMediumModel {
    public PackMedium createMedium(PackMediumInput input);
    public PackPhaseEvaluation evaluatePhase(PackPhaseQuery query);
    public PackPhaseSample samplePhase(PackPhaseSampleQuery query);
};

public interface IRayPack : IPackSurfaceModel, IPackEnvironmentModel, IPackMediumModel {
};
