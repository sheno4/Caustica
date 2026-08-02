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

float engineFresnelDielectric(float cosine, float etaI, float etaT) {
    float c = clamp(cosine, 0.0, 1.0);
    float eta = etaI / etaT;
    float sinT2 = eta * eta * max(0.0, 1.0 - c * c);
    if (sinT2 >= 1.0) return 1.0; // total internal reflection
    float cosT = sqrt(max(0.0, 1.0 - sinT2));
    float rs = (etaI * c - etaT * cosT) / (etaI * c + etaT * cosT);
    float rp = (etaT * c - etaI * cosT) / (etaT * c + etaI * cosT);
    return 0.5 * (rs * rs + rp * rp);
}

// Dielectric interfaces (water, stained glass, ice) are engine-owned in full: a pack contributes only
// the material facts (transmission, interiorIor) baked into the closure by createSurface. Fresnel
// choice between reflection and refraction, and total-internal-reflection fallback, are invariants —
// letting a pack pick its own dielectric response would let it silently break energy conservation or
// desync from the guide/reconstruction contract the same way a stylized refraction would (see
// docs/RAY_PACK_ARCHITECTURE.md section 4.4).
public EngineDielectricSample engineSampleDielectricInterface(
        PackSurfaceClosure surface, float3 outgoingDirection, float3 shadingNormal,
        float incidentIor, float transmittedIor, float selector) {
    EngineDielectricSample result;
    float cosI = clamp(dot(shadingNormal, outgoingDirection), 0.0, 1.0);
    float fresnel = engineFresnelDielectric(cosI, incidentIor, transmittedIor);
    if (selector < fresnel) {
        result.direction = reflect(-outgoingDirection, shadingNormal);
        result.throughputWeight = float3(1.0);
        result.eventFlags = PACK_EVENT_SPECULAR | PACK_EVENT_REFLECTION;
        return result;
    }
    float3 refracted = refract(-outgoingDirection, shadingNormal, incidentIor / transmittedIor);
    if (dot(refracted, refracted) == 0.0) {
        // TIR with fresnel < 1 cannot happen for a physically valid eta, but a degenerate/authored IOR
        // must still terminate in a defined reflection rather than an undefined refracted direction.
        result.direction = reflect(-outgoingDirection, shadingNormal);
        result.throughputWeight = float3(1.0);
        result.eventFlags = PACK_EVENT_SPECULAR | PACK_EVENT_REFLECTION;
        return result;
    }
    result.direction = normalize(refracted);
    result.throughputWeight = float3(clamp(surface.transmission, 0.0, 1.0));
    result.eventFlags = PACK_EVENT_SPECULAR | PACK_EVENT_TRANSMISSION;
    return result;
}

// Celestial-disc visibility is a loop invariant, not appearance: a diffuse bounce already accounted
// for direct celestial light via NEE, so showing the disc again on the next miss double-counts it. A
// specular/glossy/transmissive bounce did not run that NEE, so the disc must remain visible. The pack
// never sets this — it only classifies the sampled lobe via eventFlags.
public bool engineShowsCelestial(uint sampledEventFlags) {
    return (sampledEventFlags & (PACK_EVENT_SPECULAR | PACK_EVENT_GLOSSY | PACK_EVENT_TRANSMISSION)) != 0u;
}

// An emitter's direct-hit radiance must be gathered exactly once: if RIS already sampled it as a
// candidate at the PREVIOUS vertex, a diffuse continuation must not also add it on hit (double count);
// but if RIS did not sample it — or this bounce is a specular/glossy/transmissive one that ran no NEE
// against it — the hit must still gather it directly, or that light is lost outright.
public bool engineGatesEmitterDirectHit(bool emitterSampledByRis, bool showsCelestial) {
    return emitterSampledByRis && !showsCelestial;
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
//
// demodulation-albedo note (docs/RAY_PACK_ARCHITECTURE.md 6.1): guideDiffuseAlbedo is read directly
// from the closure the pack already produced for shading, not from a separate pack-authored guide
// value. This assumes the pack's diffuse response stays proportional to diffuseAlbedo; a pack whose
// evaluateResponse tints or palettizes that response will leave residual texture in the demodulated
// signal, which DLSS-RR will read as leftover detail to blur or smear rather than as a denoiser bug.
// If that becomes a real problem for a pack, add an explicit demodulationAlbedo() query instead of
// widening this one.
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
    output.showsCelestial = false;
    output.continuationValid = 0u;

    EngineDirectLightingResult direct = engineResolveDirectLighting(pack, input.surface,
            input.surfaceInput.outgoingDirection, directReservoir, directVisibility);
    float3 shaded = input.surface.emissionRadiance + direct.radiance;
    output.radiance = input.pathThroughput * pack.evaluateResponse(shaded, input.surface);

    output.guideDiffuseAlbedo = input.surface.diffuseAlbedo;
    output.guideSpecularAlbedo = input.surface.specularF0;
    output.guideNormal = input.surface.shadingNormal;
    output.guideRoughness = input.surface.alphaRoughness;

    uint eventFlags;
    float3 continuationDirection;
    float3 continuationWeight;
    bool haveContinuation;
    if (input.surface.transmission > 0.0) {
        // Dielectric interface: engine-owned in full, never routed through the pack's BSDF. See
        // engineSampleDielectricInterface.
        EngineDielectricSample dielectric = engineSampleDielectricInterface(input.surface,
                input.surfaceInput.outgoingDirection, input.surface.shadingNormal,
                input.incidentIor, input.transmittedIor, input.bsdfRandom.x);
        eventFlags = dielectric.eventFlags;
        continuationDirection = dielectric.direction;
        continuationWeight = dielectric.throughputWeight;
        haveContinuation = true;
    } else {
        PackBsdfSampleQuery sampleQuery;
        sampleQuery.surface = input.surface;
        sampleQuery.outgoingDirection = input.surfaceInput.outgoingDirection;
        sampleQuery.random = input.bsdfRandom;
        PackBsdfSample sample = pack.sampleBsdf(sampleQuery);
        eventFlags = sample.eventFlags;
        continuationDirection = sample.incomingDirection;
        continuationWeight = sample.throughputWeight;
        haveContinuation = sample.pdf > 0.0;
    }

    output.eventFlags = eventFlags;
    output.showsCelestial = engineShowsCelestial(eventFlags);
    output.continuationDirection = continuationDirection;
    output.continuationThroughput = input.pathThroughput * continuationWeight;
    if (haveContinuation && enginePositiveFiniteWeight(output.continuationThroughput)
            && all(isfinite(continuationDirection))) {
        output.continuationOrigin = engineOffsetSurfaceOrigin(input.surfaceInput.position,
                input.surfaceInput.geometricNormal, continuationDirection);
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
