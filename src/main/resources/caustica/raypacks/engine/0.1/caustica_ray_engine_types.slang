module caustica_ray_engine_types;

import caustica_ray_pack_api;

public static const uint ENGINE_LIGHT_FINITE_DISTANCE = 0x1u;

// Candidate generation, proposal PDFs, light-side geometry, and source identity are engine-owned.
// radianceOverProposalPdf excludes the receiver cosine and BSDF, which are evaluated at the vertex.
public struct EngineDirectLightCandidate {
    public float3 direction;
    public float3 radianceOverProposalPdf;
    public float distance;
    public uint sourceId;
    public uint flags;
};

// This reservoir is private engine state. It is not persisted by or exposed to a ray pack.
public struct EngineDirectLightingReservoir {
    public EngineDirectLightCandidate selected;
    public float selectedTarget;
    public float weightSum;
    public uint sampleCount;
    public uint valid;
};

public struct EngineDirectLightingResult {
    public float3 radiance;
    public float3 shadowDirection;
    public float shadowDistance;
    public uint sourceId;
    public uint valid;
};

public struct EngineSurfaceVertexInput {
    public PackSurfaceInput surfaceInput;
    public PackSurfaceClosure surface;
    public float3 pathThroughput;
    public float incidentIor;
    public float transmittedIor;
    public float3 bsdfRandom;
    public uint bounce;
};

// The engine consumes this continuation in its traversal loop and writes the guide fields to its
// denoiser inputs. The pack never receives or owns path scheduling state.
public struct EngineSurfaceVertexOutput {
    public float3 radiance;
    public float3 continuationOrigin;
    public float3 continuationDirection;
    public float3 continuationThroughput;
    public float3 guideDiffuseAlbedo;
    public float3 guideSpecularAlbedo;
    public float3 guideNormal;
    public float guideRoughness;
    public uint eventFlags;
    public uint continuationValid;
};

public struct EngineMissInput {
    public PackFrameContext frame;
    public float3 direction;
    public float3 pathThroughput;
    public bool showCelestialBodies;
};

public struct EngineMediumSegmentResult {
    public PackMedium medium;
    public float3 transmittance;
    public float3 emittedRadiance;
};
