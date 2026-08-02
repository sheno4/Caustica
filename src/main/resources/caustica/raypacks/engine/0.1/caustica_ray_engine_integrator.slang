module caustica_ray_engine_integrator;

import caustica_ray_pack_api;
import caustica_ray_engine_types;
import caustica_direct_lighting_service;

static const float ENGINE_SURFACE_BIAS = 0.005;

bool enginePositiveFiniteWeight(float3 value) {
    return all(isfinite(value)) && any(value > float3(0.0));
}

float3 engineOffsetSurfaceOrigin(float3 position, float3 geometricNormal, float3 direction) {
    float side = dot(geometricNormal, direction) >= 0.0 ? 1.0 : -1.0;
    return position + geometricNormal * (side * ENGINE_SURFACE_BIAS);
}

public float engineEvaluateCoverage<TPack : IPackSurfaceModel>(
        TPack pack, PackSurfaceInput input) {
    return clamp(pack.evaluateCoverage(input), 0.0, 1.0);
}

public PackSurfaceClosure engineCreateSurface<TPack : IPackSurfaceModel>(
        TPack pack, PackSurfaceInput input) {
    return pack.createSurface(input);
}

// This is one engine-owned path vertex. The ray-generation loop supplies hits, candidate streams,
// visibility, medium-stack IORs, and random values, then consumes the returned continuation.
public EngineSurfaceVertexOutput engineShadeSurfaceVertex<TPack : IPackSurfaceModel>(
        TPack pack,
        EngineSurfaceVertexInput input,
        EngineDirectLightingReservoir directReservoir,
        float directVisibility) {
    EngineSurfaceVertexOutput output;
    output.radiance = float3(0.0);
    output.continuationOrigin = input.surfaceInput.position;
    output.continuationDirection = float3(0.0);
    output.continuationThroughput = float3(0.0);
    output.guideDiffuseAlbedo = float3(0.0);
    output.guideSpecularAlbedo = float3(0.0);
    output.guideNormal = input.surfaceInput.geometricNormal;
    output.guideRoughness = 1.0;
    output.eventFlags = PACK_EVENT_NONE;
    output.continuationValid = 0u;

    EngineDirectLightingResult direct = engineResolveDirectLighting(pack, input.surface,
            input.surfaceInput.outgoingDirection, input.incidentIor, input.transmittedIor,
            directReservoir, directVisibility);
    output.radiance = input.pathThroughput * (input.surface.emissionRadiance + direct.radiance);

    output.guideDiffuseAlbedo = input.surface.diffuseAlbedo;
    output.guideSpecularAlbedo = input.surface.specularF0;
    output.guideNormal = input.surface.shadingNormal;
    output.guideRoughness = input.surface.alphaRoughness;

    PackBsdfSampleQuery sampleQuery;
    sampleQuery.surface = input.surface;
    sampleQuery.outgoingDirection = input.surfaceInput.outgoingDirection;
    sampleQuery.incidentIor = input.incidentIor;
    sampleQuery.transmittedIor = input.transmittedIor;
    sampleQuery.random = input.bsdfRandom;
    PackBsdfSample sample = pack.sampleBsdf(sampleQuery);
    output.continuationDirection = sample.incomingDirection;
    output.continuationThroughput = input.pathThroughput * sample.throughputWeight;
    output.eventFlags = sample.eventFlags;
    if (sample.pdf > 0.0 && enginePositiveFiniteWeight(output.continuationThroughput)
            && all(isfinite(sample.incomingDirection))) {
        output.continuationOrigin = engineOffsetSurfaceOrigin(input.surfaceInput.position,
                input.surfaceInput.geometricNormal, sample.incomingDirection);
        output.continuationValid = 1u;
    }
    return output;
}

public float3 engineShadeMiss<TPack : IPackEnvironmentModel>(TPack pack, EngineMissInput input) {
    PackEnvironmentQuery query;
    query.frame = input.frame;
    query.direction = input.direction;
    query.showCelestialBodies = input.showCelestialBodies;
    return input.pathThroughput * pack.evaluateEnvironment(query);
}

// Homogeneous segment integration is engine-owned. Packs provide coefficients and phase functions;
// the engine decides where segments begin and end and how volume events participate in path scheduling.
public EngineMediumSegmentResult engineIntegrateMediumSegment<TPack : IPackMediumModel>(
        TPack pack, PackMediumInput input, float distance) {
    EngineMediumSegmentResult result;
    result.medium = pack.createMedium(input);
    float3 extinction = result.medium.scattering + result.medium.absorption;
    result.transmittance = exp(-extinction * max(distance, 0.0));
    float3 safeExtinction = max(extinction, float3(1.0e-7));
    result.emittedRadiance = result.medium.emissionRadiance
            * (1.0 - result.transmittance) / safeExtinction;
    return result;
}

public PackPhaseSample engineSampleMediumPhase<TPack : IPackMediumModel>(
        TPack pack, PackMedium medium, float3 incomingDirection, float2 random) {
    PackPhaseSampleQuery query;
    query.medium = medium;
    query.incomingDirection = incomingDirection;
    query.random = random;
    return pack.samplePhase(query);
}
