module primary_rgen;

import world_common;
import world_minimal;
import bindings;
import caustica_api;
import caustica_volume;
import caustica_types;
import retained_lights;
import retained_path_queue;
import path_queue_types;
import stable_planes;
import nrd_signals;

uint primarySeed(uint2 pixel, uint frameIndex) {
    uint seed = retainedLightHash(pixel.x ^ (pixel.y * 0x9e3779b9u) ^ (frameIndex * 0x85ebca6bu));
    return seed != 0u ? seed : 0xa511e9b3u;
}

VolumeProperties initialVolumeProperties<TComposition : IComposition>(float3 position, WorldPush frame) {
    VolumeProperties vacuum = {};
    if (worldBindings.initialVolumeActive == 0u) return vacuum;
    TComposition.Volumes volumes = {};
    VolumeInput input = {};
    input.shaderRoot = worldShaderRoot();
    input.bindingData = worldBindings.initialVolumeBinding;
    input.instanceData = worldBindings.initialVolumeInstance;
    input.boundaryPosition = position;
    input.proceduralPosition = position + frame.proceduralDomainOffset;
    input.metersPerSceneUnit = neeAtState(worldBindings.neeAtStateAddress)[0].metersPerSceneUnit;
    input.time = frame.time;
    input.previousTime = frame.previousTime;
    input.evaluationFlags = (frame.flags & 16u) != 0u ? EVALUATION_ANIMATION_ENABLED : 0u;
    VolumeProperties properties = volumes.evaluateVolume(worldBindings.initialVolumeImplementation, input);
    properties.absorptionCoefficient = max(properties.absorptionCoefficient, float3(0.0));
    properties.indexOfRefraction = max(properties.indexOfRefraction, 1.0);
    return properties;
}

struct StablePlaneEndpoint {
    float3 virtualPosition;
    float3 previousVirtualPosition;
    StablePlaneReflection reflection0;
    StablePlaneReflection reflection1;
    StablePlaneReflection reflection2;
    uint reflectionCount;
    bool containsTransmission;
    uint deltaChainDepth;
};

StablePlaneEndpoint primaryStablePlaneEndpoint(WorldPayload primary) {
    StablePlaneEndpoint result = {};
    if ((primary.pathFlags & WORLD_PATH_MISS) == 0u && primary.hitDistance >= 0.0) {
        result.virtualPosition = primary.hitPosition;
        result.previousVirtualPosition = primary.previousHitPosition;
    }
    return result;
}

void stablePlaneAppendReflection(inout StablePlaneEndpoint endpoint, WorldPayload payload) {
    StablePlaneReflection plane;
    plane.currentPosition = payload.hitPosition;
    plane.previousPosition = payload.previousHitPosition;
    plane.currentNormal = unpackNormalOct(payload.guideNormal);
    plane.previousNormal = unpackNormalOct(payload.previousGuideNormal);
    if (endpoint.reflectionCount == 0u) endpoint.reflection0 = plane;
    else if (endpoint.reflectionCount == 1u) endpoint.reflection1 = plane;
    else endpoint.reflection2 = plane;
    endpoint.reflectionCount++;
}

StablePlaneEndpoint stablePlaneEndpoint(WorldPayload primary, WorldPush frame) {
    StablePlaneEndpoint result = primaryStablePlaneEndpoint(primary);
    float3 previousCamera = frame.camOffset - frame.camDelta;
    float currentPathLength = max(primary.hitDistance, 0.0);
    float previousPathLength = primary.hitDistance >= 0.0
            ? length(primary.previousHitPosition - previousCamera) : 0.0;
    float3 previousSegmentPosition = primary.previousHitPosition;
    WorldPayload current = primary;
    float tMin = 0.001 / max(
            neeAtState(worldBindings.neeAtStateAddress)[0].metersPerSceneUnit, 1.0e-8);
    for (uint segment = 0u; segment < STABLE_PLANE_MAX_DELTA_SEGMENTS; segment++) {
        if ((current.pathFlags & WORLD_PATH_GUIDE_CONTINUATION) == 0u) break;
        if (current.guideContinuationEvent == WORLD_GUIDE_EVENT_REFLECTION) {
            stablePlaneAppendReflection(result, current);
        } else if (current.guideContinuationEvent == WORLD_GUIDE_EVENT_TRANSMISSION) {
            result.containsTransmission = true;
        }
        RayDesc ray;
        ray.Origin = current.guideContinuationOrigin;
        ray.Direction = current.guideContinuationDirection;
        ray.TMin = tMin;
        ray.TMax = 10000.0;
        WorldPayload next = {};
        next.hitDistance = -1.0;
        next.seed = current.seed ^ retainedLightHash(0xa511e9b3u + segment);
        next.volumeAbsorption = current.guideContinuationAbsorption;
        next.volumeIor = current.guideContinuationIor;
        next.pathFlags = WORLD_PATH_GUIDE
                | (current.guideContinuationVolumeActive != 0u ? WORLD_PATH_VOLUME_ACTIVE : 0u);
        TraceRay(worldAccelerationStructure(worldBindings), RAY_FLAG_NONE, WORLD_RAY_MASK_SECONDARY,
                0u, 2u, 1u, ray, next);
        if (next.hitDistance >= 0.0) {
            currentPathLength += next.hitDistance;
            previousPathLength += length(next.previousHitPosition - previousSegmentPosition);
            previousSegmentPosition = next.previousHitPosition;
        }
        current = next;
        result.deltaChainDepth++;
        if ((current.pathFlags & WORLD_PATH_MISS) != 0u || current.hitDistance < 0.0) break;
    }
    if ((current.pathFlags & (WORLD_PATH_MISS | WORLD_PATH_GUIDE_CONTINUATION)) != 0u
            || current.hitDistance < 0.0) {
        return primaryStablePlaneEndpoint(primary);
    }
    float3 unfoldedCurrent = stablePlaneUnfoldPoint(current.hitPosition, false,
            result.reflection0, result.reflection1, result.reflection2, result.reflectionCount);
    float3 unfoldedPrevious = stablePlaneUnfoldPoint(current.previousHitPosition, true,
            result.reflection0, result.reflection1, result.reflection2, result.reflectionCount);
    if (!result.containsTransmission) {
        result.virtualPosition = unfoldedCurrent;
        result.previousVirtualPosition = unfoldedPrevious;
    } else {
        // Refraction keeps an optical-path approximation until the plane ABI carries an interface
        // Jacobian. Reflection interfaces are unfolded first, retaining coherent tangential motion.
        float3 currentDirection = normalize(unfoldedCurrent - frame.camOffset);
        float3 previousDirection = normalize(unfoldedPrevious - previousCamera);
        result.virtualPosition = frame.camOffset + currentDirection * currentPathLength;
        result.previousVirtualPosition = previousCamera + previousDirection * previousPathLength;
    }
    return result;
}

void writePrimaryStablePlane<TComposition : IComposition>(uint2 pixel, uint2 extent,
        WorldPush frame, WorldPayload primary, float3 radiance, float3 unexposedRadiance,
        float3 unexposedDiffuse, float3 unexposedSpecular) {
    StablePlaneEndpoint endpoint = stablePlaneEndpoint(primary, frame);
    float3 normal = float3(0.0);
    float3 albedo = float3(0.0);
    float3 specularAlbedo = float3(0.0);
    float depth = 0.0;
    float2 motion = float2(0.0);
    float2 specularMotion = float2(0.0);
    bool primaryValid = (primary.pathFlags & WORLD_PATH_MISS) == 0u && primary.hitDistance >= 0.0;
    if (primaryValid) {
        normal = unpackNormalOct(primary.guideNormal);
        albedo = retainedUnpackRgb9e5(primary.guideAlbedo);
        specularAlbedo = retainedUnpackRgb9e5(primary.guideSpecularAlbedo);
        float3 currentCameraRelative = primary.hitPosition - frame.camOffset;
        float4 currentClip = mul(frame.curViewProj, float4(currentCameraRelative, 1.0));
        if (currentClip.w > 0.0) depth = currentClip.w;
        float3 previousCameraRelative = primary.previousHitPosition
                - frame.camOffset + frame.camDelta;
        float4 previousClip = mul(frame.prevViewProj, float4(previousCameraRelative, 1.0));
        if (previousClip.w > 0.0 && currentClip.w > 0.0) {
            motion = (previousClip.xy / previousClip.w - currentClip.xy / currentClip.w)
                    * 0.5 * float2(extent);
        }
        if (endpoint.reflectionCount > 0u && !endpoint.containsTransmission) {
            float4 currentVirtualClip = mul(frame.curViewProj,
                    float4(endpoint.virtualPosition - frame.camOffset, 1.0));
            float4 previousVirtualClip = mul(frame.prevViewProj,
                    float4(endpoint.previousVirtualPosition - frame.camOffset + frame.camDelta, 1.0));
            if (currentVirtualClip.w > 0.0 && previousVirtualClip.w > 0.0) {
                specularMotion = (previousVirtualClip.xy / previousVirtualClip.w
                        - currentVirtualClip.xy / currentVirtualClip.w)
                        * 0.5 * float2(extent);
            }
        }
    }
    StablePlaneSample plane = {};
    plane.radiance = radiance;
    plane.normal = normal;
    plane.roughness = primary.roughness;
    plane.diffuseAlbedo = albedo;
    plane.specularAlbedo = specularAlbedo;
    plane.primaryDepth = depth;
    plane.primaryMotion = motion;
    plane.specularMotion = specularMotion;
    plane.emissive = (primary.pathFlags & WORLD_PATH_EMISSIVE) != 0u;
    plane.valid = primaryValid;
    plane.deltaChainDepth = endpoint.deltaChainDepth;
    plane.reflectionCount = endpoint.reflectionCount;
    plane.containsTransmission = endpoint.containsTransmission;
    stablePlaneWrite(pixel, plane);
    float3 stableRadiance = max(unexposedRadiance - unexposedDiffuse - unexposedSpecular,
            float3(0.0));
    nrdWritePrimarySignals(pixel, primaryValid, depth, primary.roughness,
            unexposedDiffuse, unexposedSpecular, stableRadiance,
            primary.diffuseHitDistance, 0.0, albedo, specularAlbedo);
}

public void runPrimary<TComposition : IComposition>() {
    uint2 pixel = DispatchRaysIndex().xy;
    uint2 extent = DispatchRaysDimensions().xy;
    ConstPtr<WorldPush> frames = (ConstPtr<WorldPush>)worldBindings.worldPushAddress;
    WorldPush frame = frames[0];
    uint pixelIndex = pixel.y * extent.x + pixel.x;

    float2 uv = (float2(pixel) + 0.5) / float2(extent);
    float2 clip = uv * 2.0 - 1.0 + frame.jitter * (2.0 / float2(extent));
    float4 farPoint = mul(frame.invViewProj, float4(clip, 0.0, 1.0));
    farPoint.xyz /= farPoint.w;

    RayDesc ray;
    // SceneView.medium describes the camera point, which is therefore the primary transport origin.
    ray.Origin = frame.camOffset;
    ray.Direction = normalize(farPoint.xyz);
    ray.TMin = 0.001 / max(neeAtState(worldBindings.neeAtStateAddress)[0].metersPerSceneUnit, 1.0e-8);
    ray.TMax = 10000.0;

    RetainedPathState emptyState = {};
    emptyState.valid = false;
    DevicePtr<PackedPathSegment> queue = (DevicePtr<PackedPathSegment>)worldBindings.pathQueueAddress;
    queue[pixelIndex] = packRetainedPath(emptyState, PATH_NO_NEXT);

    VolumeProperties initialVolume = initialVolumeProperties<TComposition>(ray.Origin, frame);
    float3 absorption = initialVolume.absorptionCoefficient;
    WorldPayload payload = {};
    payload.hitDistance = -1.0;
    payload.feedbackThroughput = float3(1.0);
    payload.volumeAbsorption = absorption;
    payload.volumeIor = initialVolume.indexOfRefraction;
    payload.previousPosition = ray.Origin;
    payload.seed = primarySeed(pixel, frame.frameIndex);
    payload.previousNeeProposalMode = NEE_AT_PROPOSAL_SCREEN_MIXTURE;
    payload.previousNeeCandidateCount = 1u;
    payload.currentNeeProposalMode = NEE_AT_PROPOSAL_SCREEN_MIXTURE;
    payload.currentNeeCandidateCount = clamp(
            neeAtState(worldBindings.neeAtStateAddress)[0].candidateCount, 1u, 8u);
    payload.queueRecordIndex = pixelIndex;
    payload.pathFlags = WORLD_PATH_REQUEST_CONTINUATION | WORLD_PATH_PRIMARY
            | (worldBindings.initialVolumeActive != 0u ? WORLD_PATH_VOLUME_ACTIVE : 0u);
    TraceRay(worldAccelerationStructure(worldBindings), RAY_FLAG_NONE, WORLD_RAY_MASK_PRIMARY,
            0u, 2u, 0u, ray, payload);

    float travel = payload.hitDistance >= 0.0 ? payload.hitDistance : ray.TMax;
    float3 transmittance = exp(-absorption * max(travel, 0.0));
    PackedPathSegment queued = queue[pixelIndex];
    RetainedPathState continuation = unpackRetainedPath(queued);
    if (continuation.valid) {
        continuation.throughput *= transmittance;
        queue[pixelIndex] = packRetainedPath(continuation, queued.nextRecord);
    }
    float3 unexposedRadiance = max(payload.radiance * transmittance, float3(0.0));
    float3 unexposedDiffuse = max(payload.diffuseRadiance * transmittance, float3(0.0));
    float3 unexposedSpecular = max(payload.specularRadiance * transmittance, float3(0.0));
    float3 radiance = clamp(unexposedRadiance * frame.preExposure,
            float3(0.0), float3(HALF_MAX));
    writePrimaryStablePlane<TComposition>(pixel, extent, frame, payload, radiance,
            unexposedRadiance, unexposedDiffuse, unexposedSpecular);
}

[shader("raygeneration")]
void main<TComposition : IComposition>() {
    runPrimary<TComposition>();
}
