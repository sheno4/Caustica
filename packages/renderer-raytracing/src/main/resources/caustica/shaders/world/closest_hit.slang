module closest_hit;

import world_minimal;
import caustica_api;
import caustica_surface;
import caustica_types;
import retained_lights;
import surface_bsdf;
import bindings;
import world_common;
import caustica_volume;
import retained_path_queue;
import path_queue_types;

float3 retainedDirectLighting(OpenPbrSurface material, SurfaceInput input, bool allowLocal,
                              float3 currentMediumAbsorption, float currentMediumIor,
                              bool currentMediumActive, float3 feedbackThroughput,
                              inout uint seed, out float3 diffuseLighting,
                              out float3 specularLighting, out float directHitDistance) {
    diffuseLighting = float3(0.0);
    specularLighting = float3(0.0);
    directHitDistance = 0.0;
    uint2 pixel = DispatchRaysIndex().xy;
    SurfaceClosure closure = surfaceClosure(material, input.geometricNormal);
    NeeAtState state = neeAtState(worldBindings.neeAtStateAddress)[0];
    uint candidateCount = clamp(state.candidateCount, 1u, 8u);
    DirectLightSample selected = invalidDirectLight();
    float3 selectedContribution = float3(0.0);
    float3 selectedDiffuseContribution = float3(0.0);
    float3 selectedSpecularContribution = float3(0.0);
    float selectedTarget = 0.0;
    float weightSum = 0.0;
    for (uint candidate = 0u; candidate < candidateCount; candidate++) {
        DirectLightSample light = sampleRetainedLight(worldBindings, input.position, pixel,
                candidate, candidateCount, allowLocal, seed);
        if (!light.valid) continue;
        float cosine = abs(dot(closure.shadingNormal, light.direction));
        if (cosine <= 0.0) continue;
        BsdfQuery query;
        query.surface = closure;
        query.outgoingDirection = input.outgoingDirection;
        query.incomingDirection = light.direction;
        BsdfEvaluation evaluation = evaluateBsdf(query);
        float3 contribution = evaluation.value * cosine * light.radianceOverShapePdf;
        float3 diffuseContribution = evaluation.diffuseValue * cosine * light.radianceOverShapePdf;
        float3 specularContribution = evaluation.specularValue * cosine * light.radianceOverShapePdf;
        if (light.linkedEmitter && light.shapePdf > 0.0) {
            float lightPdf = neeAtLightMisPdf(light.proposalPdf, light.shapePdf, candidateCount);
            diffuseContribution *= lightPdf / max(lightPdf + evaluation.diffusePdf, 1.0e-20);
            specularContribution *= lightPdf / max(lightPdf + evaluation.specularPdf, 1.0e-20);
            contribution = diffuseContribution + specularContribution;
        }
        float target = max(dot(max(contribution, float3(0.0)),
                float3(0.27222872, 0.67408177, 0.05368952)), 0.0);
        float weight = target / light.proposalPdf;
        weightSum += weight;
        if (weight > 0.0 && retainedLightRandom(seed) * weightSum <= weight) {
            selected = light;
            selectedContribution = contribution;
            selectedDiffuseContribution = diffuseContribution;
            selectedSpecularContribution = specularContribution;
            selectedTarget = target;
        }
    }
    if (!selected.valid || selectedTarget <= 0.0 || weightSum <= 0.0) return float3(0.0);
    float reservoirScale = weightSum / (float(candidateCount) * selectedTarget);
    float3 estimator = selectedContribution * reservoirScale;
    diffuseLighting = selectedDiffuseContribution * reservoirScale;
    specularLighting = selectedSpecularContribution * reservoirScale;
    float3 incomingFeedbackThroughput = feedbackThroughput
            * exp(-currentMediumAbsorption * max(RayTCurrent(), 0.0));

    WorldPayload shadow = {};
    shadow.radiance = float3(1.0);
    shadow.hitDistance = 0.0;
    shadow.previousBsdfPdf = selected.maximumDistance;
    shadow.volumeAbsorption = currentMediumAbsorption;
    shadow.volumeIor = currentMediumIor;
    shadow.pathFlags = WORLD_PATH_SHADOW
            | (currentMediumActive ? WORLD_PATH_VOLUME_ACTIVE : 0u);
    RayDesc ray;
    float rayEpsilon = 0.002 / max(state.metersPerSceneUnit, 1.0e-8);
    float3 offsetNormal = dot(input.geometricNormal, selected.direction) >= 0.0
            ? input.geometricNormal : -input.geometricNormal;
    ray.Origin = input.position + offsetNormal * rayEpsilon;
    ray.Direction = selected.direction;
    ray.TMin = 0.5 * rayEpsilon;
    ray.TMax = selected.maximumDistance;
    TraceRay(worldAccelerationStructure(worldBindings),
            RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH | RAY_FLAG_SKIP_CLOSEST_HIT_SHADER,
            WORLD_RAY_MASK_SECONDARY, 1u, 2u, 1u, ray, shadow);
    if (shadow.hitDistance >= 0.0 || !any(shadow.radiance > 0.0)) {
        diffuseLighting = float3(0.0);
        specularLighting = float3(0.0);
        return float3(0.0);
    }
    estimator *= shadow.radiance;
    diffuseLighting *= shadow.radiance;
    specularLighting *= shadow.radiance;
    directHitDistance = selected.signalDistance;
    float feedback = dot(max(incomingFeedbackThroughput * estimator, float3(0.0)),
            float3(0.27222872, 0.67408177, 0.05368952));
    neeAtWriteFeedback(worldBindings, pixel, selected.lightIndex, feedback);
    return estimator;
}

VolumeProperties retainedVolumeProperties<TComposition : IComposition>(RetainedGeometryRecord record,
                                                                        SurfaceInput surfaceInput) {
    TComposition.Volumes volumes = {};
    VolumeInput input = {};
    input.shaderRoot = surfaceInput.shaderRoot;
    input.bindingData = record.volumeBinding;
    input.instanceData = record.instanceData;
    input.boundaryPosition = surfaceInput.position;
    input.proceduralPosition = surfaceInput.proceduralPosition;
    input.metersPerSceneUnit = surfaceInput.metersPerSceneUnit;
    input.time = surfaceInput.time;
    input.previousTime = surfaceInput.previousTime;
    input.evaluationFlags = surfaceInput.evaluationFlags & EVALUATION_ANIMATION_ENABLED;
    return volumes.evaluateVolume(record.volumeImplementation, input);
}

float retainedDielectricFresnel(float cosine, float etaIncident, float etaTransmitted) {
    float sineTransmittedSquared = (etaIncident * etaIncident) / (etaTransmitted * etaTransmitted)
            * max(0.0, 1.0 - cosine * cosine);
    if (sineTransmittedSquared >= 1.0) return 1.0;
    float transmittedCosine = sqrt(1.0 - sineTransmittedSquared);
    float rs = (etaIncident * cosine - etaTransmitted * transmittedCosine)
            / (etaIncident * cosine + etaTransmitted * transmittedCosine);
    float rp = (etaIncident * transmittedCosine - etaTransmitted * cosine)
            / (etaIncident * transmittedCosine + etaTransmitted * cosine);
    return 0.5 * (rs * rs + rp * rp);
}

float retainedVolumeBoundaryWeight(RetainedGeometryRecord record, OpenPbrSurface surface) {
    if ((record.flags & RETAINED_GEOMETRY_HAS_VOLUME) == 0u) return 0.0;
    float dielectricWeight = 1.0 - clamp(surface.base_metalness, 0.0, 1.0);
    return dielectricWeight * clamp(surface.transmission_weight, 0.0, 1.0);
}

void retainedSelectStablePlaneContinuation<TComposition : IComposition>(inout WorldPayload payload,
        RetainedGeometryRecord record, SurfaceInput input, OpenPbrSurface surface,
        SurfaceClosure closure) {
    payload.pathFlags &= ~WORLD_PATH_GUIDE_CONTINUATION;
    payload.guideContinuationEvent = WORLD_GUIDE_EVENT_NONE;
    payload.guideContinuationAbsorption = payload.volumeAbsorption;
    payload.guideContinuationIor = payload.volumeIor;
    payload.guideContinuationVolumeActive =
            (payload.pathFlags & WORLD_PATH_VOLUME_ACTIVE) != 0u ? 1u : 0u;
    if (surface.specular_roughness > 0.08) return;

    float3 incident = -input.outgoingDirection;
    bool volumeBoundary = (record.flags & RETAINED_GEOMETRY_HAS_VOLUME) != 0u;
    float boundaryWeight = retainedVolumeBoundaryWeight(record, surface);
    float cosine = max(dot(closure.shadingNormal, input.outgoingDirection), 0.0);
    float reflectionScore;
    float transmissionScore;
    float diffuseScore;
    float3 transmitted = incident;
    float etaTransmitted = 1.0;
    VolumeProperties targetVolume = {};
    if (volumeBoundary) {
        OpenPbrSurface opaqueSurface = surface;
        opaqueSurface.transmission_weight = 0.0;
        SurfaceClosure opaqueClosure = surfaceClosure(opaqueSurface, input.geometricNormal);
        float4 opaqueProbabilities = surfaceLobeProbabilities(opaqueClosure, cosine);
        reflectionScore = (1.0 - boundaryWeight) * opaqueProbabilities.x;
        diffuseScore = (1.0 - boundaryWeight)
                * (opaqueProbabilities.y + opaqueProbabilities.z);
        transmissionScore = 0.0;

        bool entering = worldHitEntering();
        float etaIncident = (payload.pathFlags & WORLD_PATH_VOLUME_ACTIVE) != 0u
                ? max(payload.volumeIor, 1.0) : 1.0;
        if (entering) targetVolume = retainedVolumeProperties<TComposition>(record, input);
        etaTransmitted = entering
                ? max(targetVolume.indexOfRefraction, 1.0) : 1.0;
        float fresnel = retainedDielectricFresnel(cosine, etaIncident, etaTransmitted);
        transmitted = refract(incident, closure.shadingNormal, etaIncident / etaTransmitted);
        reflectionScore += boundaryWeight * fresnel;
        float etaScale = (etaIncident * etaIncident) / (etaTransmitted * etaTransmitted);
        float3 transmissionColor = clamp(surface.transmission_color,
                float3(0.0), float3(1.0));
        transmissionScore = dot(transmitted, transmitted) > 0.0
                ? boundaryWeight * (1.0 - fresnel) * etaScale
                        * max(transmissionColor.x, max(transmissionColor.y, transmissionColor.z))
                : 0.0;
    } else {
        float4 probabilities = surfaceLobeProbabilities(closure, cosine);
        reflectionScore = probabilities.x;
        diffuseScore = probabilities.y + probabilities.z;
        transmissionScore = probabilities.w;
    }

    float3 direction;
    if (reflectionScore >= transmissionScore && reflectionScore >= diffuseScore
            && reflectionScore > 0.0) {
        direction = normalize(reflect(incident, closure.shadingNormal));
        payload.guideContinuationEvent = WORLD_GUIDE_EVENT_REFLECTION;
    } else if (transmissionScore > reflectionScore && transmissionScore >= diffuseScore) {
        direction = normalize(transmitted);
        payload.guideContinuationEvent = WORLD_GUIDE_EVENT_TRANSMISSION;
        if (volumeBoundary && worldHitEntering()) {
            payload.guideContinuationAbsorption =
                    max(targetVolume.absorptionCoefficient, float3(0.0));
            payload.guideContinuationIor = etaTransmitted;
            payload.guideContinuationVolumeActive = 1u;
        } else if (volumeBoundary) {
            payload.guideContinuationAbsorption = float3(0.0);
            payload.guideContinuationIor = 1.0;
            payload.guideContinuationVolumeActive = 0u;
        }
    } else {
        return;
    }

    float epsilon = 0.002 / max(input.metersPerSceneUnit, 1.0e-8);
    float3 offsetNormal = dot(input.geometricNormal, direction) >= 0.0
            ? input.geometricNormal : -input.geometricNormal;
    payload.guideContinuationOrigin = input.position + offsetNormal * epsilon;
    payload.guideContinuationDirection = direction;
    payload.pathFlags |= WORLD_PATH_GUIDE_CONTINUATION;
}

BsdfSample retainedSampleContinuation<TComposition : IComposition>(inout uint seed, WorldPayload payload,
        RetainedGeometryRecord record, SurfaceInput input, OpenPbrSurface surface,
        SurfaceClosure closure, out bool refractedBoundary, out float reversePdf) {
    refractedBoundary = false;
    reversePdf = 0.0;
    bool volumeBoundary = (record.flags & RETAINED_GEOMETRY_HAS_VOLUME) != 0u;
    float boundaryWeight = retainedVolumeBoundaryWeight(record, surface);
    if (boundaryWeight > 0.0 && retainedLightRandom(seed) < boundaryWeight) {
        bool entering = worldHitEntering();
        float etaIncident = (payload.pathFlags & WORLD_PATH_VOLUME_ACTIVE) != 0u
                ? max(payload.volumeIor, 1.0) : 1.0;
        VolumeProperties targetVolume = {};
        if (entering) targetVolume = retainedVolumeProperties<TComposition>(record, input);
        float etaTransmitted = entering ? max(targetVolume.indexOfRefraction, 1.0) : 1.0;
        float3 normal = closure.shadingNormal;
        float3 incident = -input.outgoingDirection;
        float cosine = max(dot(normal, input.outgoingDirection), 0.0);
        float fresnel = retainedDielectricFresnel(cosine, etaIncident, etaTransmitted);
        float3 transmitted = refract(incident, normal, etaIncident / etaTransmitted);
        bool reflectEvent = dot(transmitted, transmitted) == 0.0
                || retainedLightRandom(seed) < fresnel;
        BsdfSample result = {};
        result.pdf = 1.0;
        if (reflectEvent) {
            result.incomingDirection = normalize(reflect(incident, normal));
            result.throughputWeight = float3(1.0);
            result.eventFlags = EVENT_GLOSSY | EVENT_REFLECTION;
        } else {
            result.incomingDirection = normalize(transmitted);
            float etaScale = (etaIncident * etaIncident) / (etaTransmitted * etaTransmitted);
            result.throughputWeight = clamp(surface.transmission_color, float3(0.0), float3(1.0))
                    * etaScale;
            result.eventFlags = EVENT_GLOSSY | EVENT_TRANSMISSION;
            refractedBoundary = true;
        }
        return result;
    }

    OpenPbrSurface sampledSurface = surface;
    if (volumeBoundary) sampledSurface.transmission_weight = 0.0;
    BsdfSampleQuery query;
    query.surface = surfaceClosure(sampledSurface, input.geometricNormal);
    query.outgoingDirection = input.outgoingDirection;
    float selectorRandom = retainedLightRandom(seed);
    if ((payload.pathFlags & WORLD_PATH_PRIMARY) != 0u) {
        uint2 pixel = DispatchRaysIndex().xy;
        ConstPtr<WorldPush> frames = (ConstPtr<WorldPush>)worldBindings.worldPushAddress;
        uint pattern = ((pixel.x & 3u) + 4u * (pixel.y & 3u)) * 5u + 3u;
        float rotation = retainedLightHash(frames[0].frameIndex * 0x9e3779b9u)
                * (1.0 / 4294967296.0);
        selectorRandom = frac((float(pattern & 15u) + 0.5) * (1.0 / 16.0) + rotation);
    }
    query.random = float3(selectorRandom, retainedLightRandom(seed),
            retainedLightRandom(seed));
    BsdfSample result = sampleBsdf(query);
    reversePdf = (volumeBoundary ? 1.0 - boundaryWeight : 1.0) * result.pdf;
    return result;
}

void retainedWriteContinuation<TComposition : IComposition>(inout WorldPayload payload,
        RetainedGeometryRecord record, SurfaceInput input, OpenPbrSurface surface,
        SurfaceClosure closure) {
    if ((payload.pathFlags & WORLD_PATH_REQUEST_CONTINUATION) == 0u) return;
    uint seed = payload.seed;
    bool refractedBoundary;
    float reversePdf;
    BsdfSample sample = retainedSampleContinuation<TComposition>(seed, payload, record, input, surface, closure,
            refractedBoundary, reversePdf);
    if (sample.pdf <= 0.0 || !any(sample.throughputWeight > 0.0)) return;

    bool volumeActive = (payload.pathFlags & WORLD_PATH_VOLUME_ACTIVE) != 0u;
    float3 absorption = payload.volumeAbsorption;
    float volumeIor = payload.volumeIor;
    bool crossesVolume = refractedBoundary;
    if (crossesVolume) {
        if (worldHitEntering()) {
            VolumeProperties volume = retainedVolumeProperties<TComposition>(record, input);
            absorption = max(volume.absorptionCoefficient, float3(0.0));
            volumeIor = max(volume.indexOfRefraction, 1.0);
            volumeActive = true;
        } else {
            absorption = float3(0.0);
            volumeIor = 1.0;
            volumeActive = false;
        }
    }

    float epsilon = 0.002 / max(input.metersPerSceneUnit, 1.0e-8);
    float3 offsetNormal = dot(input.geometricNormal, sample.incomingDirection) >= 0.0
            ? input.geometricNormal : -input.geometricNormal;
    RetainedPathState next;
    next.origin = input.position + offsetNormal * epsilon;
    next.direction = normalize(sample.incomingDirection);
    next.throughput = max(sample.throughputWeight, float3(0.0));
    next.absorption = absorption;
    next.previousBsdfPdf = reversePdf;
    next.volumeIor = volumeIor;
    next.seed = seed;
    next.proposalMode = payload.currentNeeProposalMode;
    next.candidateCount = payload.currentNeeCandidateCount;
    next.signalHitDistance = (payload.pathFlags & WORLD_PATH_PRIMARY) != 0u
            ? 0.0 : payload.signalHitDistance + max(payload.hitDistance, 0.0);
    next.firstLobeEvent = payload.firstLobeEvent != EVENT_NONE
            ? payload.firstLobeEvent : sample.eventFlags;
    next.volumeActive = volumeActive;
    next.valid = true;
    DevicePtr<PackedPathSegment> queue = (DevicePtr<PackedPathSegment>)worldBindings.pathQueueAddress;
    queue[payload.queueRecordIndex] = packRetainedPath(next, PATH_NO_NEXT);
}

float retainedEmissionMis(WorldPayload payload, RetainedGeometryRecord record, float3 hitPosition) {
    if (payload.previousBsdfPdf <= 0.0) return 1.0;
    uint lightIndex = worldEmitterLightIndex(record);
    if (lightIndex == NEE_AT_NO_LIGHT) return 1.0;
    float lightPdf = neeAtReverseParallelogramPdf(worldBindings, DispatchRaysIndex().xy, lightIndex,
            payload.previousPosition, hitPosition, payload.previousNeeProposalMode,
            payload.previousNeeCandidateCount);
    return payload.previousBsdfPdf / max(payload.previousBsdfPdf + lightPdf, 1.0e-20);
}

public void runClosestHit<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    TComposition.Surfaces surfaces = {};
    var record = worldGeometryRecord();
    SurfaceInput input = worldSurfaceInput(attributes);
    OpenPbrSurface surface = surfaces.evaluateSurface(record.surfaceImplementation, input);
    surface.geometry_thin_walled = (record.flags & RETAINED_GEOMETRY_HAS_VOLUME) == 0u;
    SurfaceClosure closure = surfaceClosure(surface, input.geometricNormal);
    payload.hitDistance = RayTCurrent();
    payload.hitPosition = input.position;
    payload.previousHitPosition = worldPreviousHitPosition(attributes, record);
    payload.roughness = clamp(surface.specular_roughness, 0.0, 1.0);
    payload.guideNormal = packNormalOct(closure.shadingNormal);
    float3 previousNormal = any(surface.previous_geometry_normal != 0.0)
            ? normalize(surface.previous_geometry_normal) : input.previousGeometricNormal;
    payload.previousGuideNormal = packNormalOct(previousNormal);
    payload.guideAlbedo = retainedPackRgb9e5(clamp(closure.diffuseAlbedo, 0.0, 1.0));
    payload.guideSpecularAlbedo = retainedPackRgb9e5(closure.specularF0);
    payload.pathFlags &= ~WORLD_PATH_MISS;
    retainedSelectStablePlaneContinuation<TComposition>(payload, record, input, surface, closure);
    float3 emission = max(surface.emission_color, float3(0.0))
            * max(surface.emission_luminance, 0.0);
    if (any(emission > 0.0)) payload.pathFlags |= WORLD_PATH_EMISSIVE;
    if ((payload.pathFlags & WORLD_PATH_GUIDE) != 0u) return;
    uint pathSeed = payload.seed;
    float3 diffuseLighting;
    float3 specularLighting;
    float directHitDistance;
    float3 directLighting = retainedDirectLighting(surface, input,
                    payload.currentNeeProposalMode == NEE_AT_PROPOSAL_SCREEN_MIXTURE,
                    payload.volumeAbsorption, payload.volumeIor,
                    (payload.pathFlags & WORLD_PATH_VOLUME_ACTIVE) != 0u,
                    payload.feedbackThroughput, pathSeed, diffuseLighting, specularLighting,
                    directHitDistance);
    payload.seed = pathSeed;
    payload.diffuseRadiance = diffuseLighting;
    payload.specularRadiance = specularLighting;
    payload.diffuseHitDistance = any(diffuseLighting > 0.0) ? directHitDistance : 0.0;
    float3 emittedRadiance = emission * retainedEmissionMis(payload, record, input.position);
    payload.radiance = emittedRadiance + directLighting;
    if ((payload.pathFlags & WORLD_PATH_PRIMARY) != 0u) {
        payload.primaryStableRadiance = emittedRadiance;
    }
    retainedWriteContinuation<TComposition>(payload, record, input, surface, closure);
}

[shader("closesthit")]
void main<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    runClosestHit<TComposition>(payload, attributes);
}
