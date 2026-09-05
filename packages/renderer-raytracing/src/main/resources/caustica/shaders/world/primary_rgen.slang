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
import stable_plane_types;
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
    float3 terminalRayDirection;
    float3x3 imageTransform;
    WorldPayload terminal;
    bool containsTransmission;
    uint deltaChainDepth;
    uint branchId;
    uint firstEvent;
    float branchWeight;
    float firstSpecularHitDistance;
    bool sky;
    bool available;
};

StablePlaneEndpoint primaryStablePlaneEndpoint(WorldPayload primary, WorldPush frame,
        float3 cameraDirection) {
    StablePlaneEndpoint result = {};
    result.terminal = primary;
    result.imageTransform = float3x3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0);
    result.terminalRayDirection = cameraDirection;
    result.available = true;
    result.branchId = 0u;
    if ((primary.pathFlags & WORLD_PATH_MISS) == 0u && primary.hitDistance >= 0.0) {
        result.virtualPosition = primary.hitPosition;
        result.previousVirtualPosition = primary.previousHitPosition;
    } else {
        result.sky = true;
        result.virtualPosition = frame.camOffset + cameraDirection * 10000.0;
        result.previousVirtualPosition = result.virtualPosition;
    }
    return result;
}

void stablePlaneAppendReflection(inout StablePlaneEndpoint endpoint, WorldPayload payload) {
    float3 n = unpackNormalOct(payload.guideNormal);
    float3x3 reflection = float3x3(
            1.0 - 2.0 * n.x * n.x, -2.0 * n.x * n.y, -2.0 * n.x * n.z,
            -2.0 * n.y * n.x, 1.0 - 2.0 * n.y * n.y, -2.0 * n.y * n.z,
            -2.0 * n.z * n.x, -2.0 * n.z * n.y, 1.0 - 2.0 * n.z * n.z);
    endpoint.imageTransform = mul(endpoint.imageTransform, reflection);
}

float3x3 stablePlaneRotationFromTo(float3 fromDirection, float3 toDirection) {
    float3 from = normalize(fromDirection);
    float3 to = normalize(toDirection);
    float cosine = clamp(dot(from, to), -1.0, 1.0);
    float3 crossValue = cross(from, to);
    float sine = length(crossValue);
    if (sine < 1.0e-5) {
        if (cosine > 0.0) return float3x3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0);
        float3 axis = normalize(abs(from.z) < 0.9
                ? cross(from, float3(0.0, 0.0, 1.0))
                : cross(from, float3(0.0, 1.0, 0.0)));
        return float3x3(
                2.0 * axis.x * axis.x - 1.0, 2.0 * axis.x * axis.y, 2.0 * axis.x * axis.z,
                2.0 * axis.y * axis.x, 2.0 * axis.y * axis.y - 1.0, 2.0 * axis.y * axis.z,
                2.0 * axis.z * axis.x, 2.0 * axis.z * axis.y, 2.0 * axis.z * axis.z - 1.0);
    }
    float3 axis = crossValue / sine;
    float oneMinusCosine = 1.0 - cosine;
    return float3x3(
            cosine + axis.x * axis.x * oneMinusCosine,
            axis.x * axis.y * oneMinusCosine - axis.z * sine,
            axis.x * axis.z * oneMinusCosine + axis.y * sine,
            axis.y * axis.x * oneMinusCosine + axis.z * sine,
            cosine + axis.y * axis.y * oneMinusCosine,
            axis.y * axis.z * oneMinusCosine - axis.x * sine,
            axis.z * axis.x * oneMinusCosine - axis.y * sine,
            axis.z * axis.y * oneMinusCosine + axis.x * sine,
            cosine + axis.z * axis.z * oneMinusCosine);
}

StablePlaneEndpoint stablePlaneEndpoint(WorldPayload primary, WorldPush frame,
        float3 cameraDirection, bool alternateFirst) {
    StablePlaneEndpoint result = {};
    uint initialEvent = alternateFirst
            ? primary.guideAlternateEvent : primary.guideContinuationEvent;
    if (initialEvent == WORLD_GUIDE_EVENT_NONE) return result;
    result.available = true;
    result.imageTransform = float3x3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0);
    result.terminalRayDirection = cameraDirection;
    result.firstEvent = initialEvent;
    result.branchWeight = alternateFirst
            ? primary.guideAlternateWeight : primary.guideContinuationWeight;
    float currentPathLength = max(primary.hitDistance, 0.0);
    WorldPayload current = primary;
    float tMin = 0.001 / max(
            neeAtState(worldBindings.neeAtStateAddress)[0].metersPerSceneUnit, 1.0e-8);
    for (uint segment = 0u; segment < STABLE_PLANE_MAX_DELTA_SEGMENTS; segment++) {
        uint event = segment == 0u && alternateFirst
                ? current.guideAlternateEvent : current.guideContinuationEvent;
        if (event == WORLD_GUIDE_EVENT_NONE) break;
        if (segment > 0u) result.branchWeight *= current.guideContinuationWeight;
        if (event == WORLD_GUIDE_EVENT_REFLECTION) {
            stablePlaneAppendReflection(result, current);
        } else if (event == WORLD_GUIDE_EVENT_TRANSMISSION) {
            result.containsTransmission = true;
        }
        result.branchId = (result.branchId << 2u) | event;
        RayDesc ray;
        ray.Origin = segment == 0u && alternateFirst
                ? current.guideAlternateOrigin : current.guideContinuationOrigin;
        ray.Direction = segment == 0u && alternateFirst
                ? current.guideAlternateDirection : current.guideContinuationDirection;
        if (event == WORLD_GUIDE_EVENT_TRANSMISSION) {
            result.imageTransform = mul(result.imageTransform,
                    stablePlaneRotationFromTo(ray.Direction, result.terminalRayDirection));
        }
        ray.TMin = tMin;
        ray.TMax = 10000.0;
        WorldPayload next = {};
        next.hitDistance = -1.0;
        next.seed = current.seed ^ retainedLightHash(0xa511e9b3u + segment);
        next.volumeAbsorption = segment == 0u && alternateFirst
                ? current.guideAlternateAbsorption : current.guideContinuationAbsorption;
        next.volumeIor = segment == 0u && alternateFirst
                ? current.guideAlternateIor : current.guideContinuationIor;
        uint volumeActive = segment == 0u && alternateFirst
                ? current.guideAlternateVolumeActive : current.guideContinuationVolumeActive;
        next.pathFlags = WORLD_PATH_GUIDE
                | (volumeActive != 0u ? WORLD_PATH_VOLUME_ACTIVE : 0u);
        TraceRay(worldAccelerationStructure(worldBindings), RAY_FLAG_NONE, WORLD_RAY_MASK_SECONDARY,
                0u, 2u, 1u, ray, next);
        if (segment == 0u && event == WORLD_GUIDE_EVENT_REFLECTION
                && next.hitDistance > 0.0) {
            result.firstSpecularHitDistance = next.hitDistance;
        }
        if (next.hitDistance >= 0.0) {
            currentPathLength += next.hitDistance;
        }
        current = next;
        result.terminalRayDirection = ray.Direction;
        result.deltaChainDepth++;
        if ((current.pathFlags & WORLD_PATH_MISS) != 0u || current.hitDistance < 0.0) break;
    }
    result.terminal = current;
    result.sky = (current.pathFlags & WORLD_PATH_MISS) != 0u || current.hitDistance < 0.0;
    float sceneLength = result.sky ? 10000.0 : currentPathLength;
    result.virtualPosition = frame.camOffset + cameraDirection * sceneLength;
    float3 worldMotion = result.sky ? float3(0.0)
            : current.previousHitPosition - current.hitPosition;
    result.previousVirtualPosition = result.virtualPosition
            + mul(result.imageTransform, worldMotion);
    return result;
}

float3 stablePlaneEndpointNormal(StablePlaneEndpoint endpoint, float3 stableSkyAlbedo,
        out float roughness, out float3 diffuseEstimate, out float3 specularEstimate) {
    float3 normal;
    if (endpoint.sky) {
        float3 skyRadiance = max(endpoint.terminal.radiance, float3(0.0));
        float skyMaximum = max(skyRadiance.x, max(skyRadiance.y, skyRadiance.z));
        float3 skyAlbedo = sqrt(skyRadiance / (float3(1.0) + skyMaximum));
        normal = -endpoint.terminalRayDirection;
        roughness = 1.0;
        diffuseEstimate = any(skyRadiance > 0.0) ? skyAlbedo : stableSkyAlbedo;
        specularEstimate = float3(0.0);
    } else {
        normal = unpackNormalOct(endpoint.terminal.guideNormal);
        roughness = endpoint.terminal.roughness;
        diffuseEstimate = retainedUnpackRgb9e5(endpoint.terminal.guideAlbedo);
        specularEstimate = retainedUnpackRgb9e5(endpoint.terminal.guideSpecularAlbedo);
    }
    return normalize(mul(endpoint.imageTransform, normal));
}

void stablePlaneDepthMotion(StablePlaneEndpoint endpoint, uint2 extent, WorldPush frame,
        out float depth, out float viewZ, out float2 motion) {
    depth = 0.0;
    viewZ = 65504.0;
    motion = float2(0.0);
    float4 currentClip = mul(frame.curViewProj,
            float4(endpoint.virtualPosition - frame.camOffset, 1.0));
    float4 previousClip = mul(frame.prevViewProj,
            float4(endpoint.previousVirtualPosition - frame.camOffset + frame.camDelta, 1.0));
    if (!endpoint.sky && currentClip.w > 0.0) {
        depth = saturate(currentClip.z / currentClip.w);
        viewZ = currentClip.w;
    }
    if (currentClip.w > 0.0 && previousClip.w > 0.0) {
        motion = (previousClip.xy / previousClip.w - currentClip.xy / currentClip.w)
                * 0.5 * float2(extent);
    }
}

void stablePlaneWriteRecord(uint2 pixel, uint2 extent, uint planeIndex,
        StablePlaneEndpoint endpoint, bool dominant, float3 normal, float roughness,
        float3 diffuseEstimate, float3 specularEstimate, float3 noisyDiffuse,
        float3 noisySpecular, float dominantSpecularHitDistance) {
    StablePlaneRecord record = stablePlaneInvalidRecord();
    if (endpoint.available) {
        record.currentVirtualPosition = endpoint.virtualPosition;
        record.roughness = roughness;
        record.previousVirtualPosition = endpoint.previousVirtualPosition;
        record.throughputLuminance = endpoint.branchWeight;
        record.normal = normal;
        record.diffuseHitDistance = dominant ? endpoint.terminal.diffuseHitDistance : 0.0;
        record.diffuseBsdfEstimate = diffuseEstimate;
        record.specularHitDistance = dominant ? dominantSpecularHitDistance : 0.0;
        record.specularBsdfEstimate = specularEstimate;
        record.branchWeight = endpoint.branchWeight;
        record.noisyDiffuseRadiance = noisyDiffuse;
        record.noisySpecularRadiance = noisySpecular;
        record.flags = (endpoint.sky ? STABLE_PLANE_FLAG_SKY : STABLE_PLANE_FLAG_VALID)
                | (dominant ? STABLE_PLANE_FLAG_DOMINANT : 0u)
                | (endpoint.containsTransmission ? STABLE_PLANE_FLAG_TRANSMISSION : 0u)
                | (min(endpoint.deltaChainDepth, 255u) << 8u);
        record.branchId = endpoint.branchId;
    }
    stablePlaneStoreRecord(pixel, extent, planeIndex, record);
}

void writePrimaryStablePlane<TComposition : IComposition>(uint2 pixel, uint2 extent,
        WorldPush frame, WorldPayload primary, float3 radiance, float3 unexposedStableRadiance,
        float3 unexposedDiffuse, float3 unexposedSpecular, float3 cameraDirection) {
    StablePlaneEndpoint endpoints[STABLE_PLANE_COUNT];
    endpoints[0] = primaryStablePlaneEndpoint(primary, frame, cameraDirection);
    endpoints[1] = stablePlaneEndpoint(primary, frame, cameraDirection, false);
    endpoints[2] = stablePlaneEndpoint(primary, frame, cameraDirection, true);
    bool primaryReplacement = endpoints[1].available && !endpoints[2].available
            && endpoints[1].firstEvent == WORLD_GUIDE_EVENT_REFLECTION
            && primary.guideBaseWeight <= 1.0e-5
            && primary.guideTransmissionWeight <= 0.0;
    if (primaryReplacement) {
        endpoints[0] = endpoints[1];
        endpoints[0].branchWeight = 1.0;
        endpoints[1] = {};
    }
    endpoints[0].branchWeight = max(0.0,
            1.0 - endpoints[1].branchWeight - endpoints[2].branchWeight);

    uint dominantIndex = 0u;
    if (endpoints[1].available && endpoints[1].branchWeight > endpoints[0].branchWeight) {
        dominantIndex = 1u;
    }
    if (endpoints[2].available
            && endpoints[2].branchWeight > endpoints[dominantIndex].branchWeight) {
        dominantIndex = 2u;
    }

    float3 stableAlbedo = sqrt(unexposedStableRadiance
            / (float3(1.0) + max(unexposedStableRadiance.x,
                    max(unexposedStableRadiance.y, unexposedStableRadiance.z))));
    float stableAlbedoAverage = dot(stableAlbedo, float3(1.0 / 3.0));
    float weights[STABLE_PLANE_COUNT];
    float weightSum = 0.0;
    for (uint planeIndex = 0u; planeIndex < STABLE_PLANE_COUNT; planeIndex++) {
        weights[planeIndex] = endpoints[planeIndex].available
                ? endpoints[planeIndex].branchWeight * 0.2 + 0.01 : 0.0;
        if (planeIndex == dominantIndex) weights[planeIndex] += 0.05;
        weightSum += weights[planeIndex];
    }
    float3 normals[STABLE_PLANE_COUNT];
    float roughnesses[STABLE_PLANE_COUNT];
    float3 diffuseEstimates[STABLE_PLANE_COUNT];
    float3 specularEstimates[STABLE_PLANE_COUNT];
    float3 normal = float3(0.0, 0.0, 1.0e-6);
    float roughness = 0.0;
    float3 albedo = float3(0.0);
    float3 specularAlbedo = float3(0.0);
    for (uint planeIndex = 0u; planeIndex < STABLE_PLANE_COUNT; planeIndex++) {
        if (!endpoints[planeIndex].available) continue;
        normals[planeIndex] = stablePlaneEndpointNormal(endpoints[planeIndex], stableAlbedo,
                roughnesses[planeIndex], diffuseEstimates[planeIndex],
                specularEstimates[planeIndex]);
        if (endpoints[planeIndex].sky) continue;
        float weight = weights[planeIndex] / max(weightSum, 1.0e-6);
        normal += normals[planeIndex] * weight;
        roughness += roughnesses[planeIndex] * weight;
        albedo += diffuseEstimates[planeIndex] * weight;
        specularAlbedo += specularEstimates[planeIndex] * weight;
    }
    normal = normalize(normal);
    float3 stableAlbedoGreyMix = lerp(stableAlbedo, float3(0.5), 0.2);
    float stableMix = stableAlbedoAverage
            / (dot(albedo, float3(1.0 / 3.0)) + sqrt(stableAlbedoAverage) + 1.0e-7);
    albedo = lerp(albedo, stableAlbedoGreyMix, saturate(stableMix));
    if (dot(albedo + specularAlbedo, float3(1.0 / 3.0)) < 0.05) {
        albedo += float3(0.05);
    }

    float depth;
    float viewZ;
    float2 motion;
    stablePlaneDepthMotion(endpoints[dominantIndex], extent, frame, depth, viewZ, motion);
    float2 specularMotion = motion;
    float dominantSpecularHitDistance = 0.0;
    bool primarySurface = (primary.pathFlags & WORLD_PATH_MISS) == 0u
            && primary.hitDistance >= 0.0;
    if (primarySurface && roughness < 0.25) {
        for (uint planeIndex = 1u; planeIndex < STABLE_PLANE_COUNT; planeIndex++) {
            if (endpoints[planeIndex].available
                    && endpoints[planeIndex].firstEvent == WORLD_GUIDE_EVENT_REFLECTION
                    && endpoints[planeIndex].firstSpecularHitDistance > 0.001) {
                dominantSpecularHitDistance = endpoints[planeIndex].firstSpecularHitDistance;
                float3 imagePosition = primary.hitPosition + cameraDirection
                        * endpoints[planeIndex].firstSpecularHitDistance;
                float4 currentClip = mul(frame.curViewProj,
                        float4(imagePosition - frame.camOffset, 1.0));
                float4 previousClip = mul(frame.prevViewProj,
                        float4(imagePosition - frame.camOffset + frame.camDelta, 1.0));
                if (currentClip.w > 0.0 && previousClip.w > 0.0) {
                    specularMotion = (previousClip.xy / previousClip.w
                            - currentClip.xy / currentClip.w) * 0.5 * float2(extent);
                }
                break;
            }
        }
    }

    for (uint planeIndex = 0u; planeIndex < STABLE_PLANE_COUNT; planeIndex++) {
        if (!endpoints[planeIndex].available) {
            stablePlaneStoreRecord(pixel, extent, planeIndex, stablePlaneInvalidRecord());
            continue;
        }
        stablePlaneWriteRecord(pixel, extent, planeIndex, endpoints[planeIndex],
                planeIndex == dominantIndex, normals[planeIndex], roughnesses[planeIndex],
                diffuseEstimates[planeIndex], specularEstimates[planeIndex],
                planeIndex == 0u ? unexposedDiffuse : float3(0.0),
                planeIndex == 0u ? unexposedSpecular : float3(0.0),
                dominantSpecularHitDistance);
    }

    bool dominantValid = !endpoints[dominantIndex].sky;
    StablePlaneSample plane = {};
    plane.radiance = radiance;
    plane.normal = normal;
    plane.roughness = roughness;
    plane.diffuseAlbedo = albedo;
    plane.specularAlbedo = specularAlbedo;
    plane.primaryDepth = depth;
    plane.primaryMotion = motion;
    plane.specularMotion = specularMotion;
    plane.emissive = (primary.pathFlags & WORLD_PATH_EMISSIVE) != 0u;
    plane.valid = dominantValid;
    stablePlaneWrite(pixel, plane);
    uint availableCount = 0u;
    for (uint planeIndex = 0u; planeIndex < STABLE_PLANE_COUNT; planeIndex++) {
        availableCount += endpoints[planeIndex].available ? 1u : 0u;
    }
    worldFloat4StorageImage(worldBindings.resources.stablePlaneMetadataImage)[pixel] = float4(
            float(availableCount) / float(STABLE_PLANE_COUNT), float(dominantIndex) * 0.5,
            float(min(endpoints[dominantIndex].deltaChainDepth,
                    STABLE_PLANE_MAX_DELTA_SEGMENTS)) / float(STABLE_PLANE_MAX_DELTA_SEGMENTS),
            endpoints[dominantIndex].containsTransmission ? 1.0 : 0.0);
    nrdWritePrimarySignals(pixel, dominantValid, viewZ, roughness,
            unexposedDiffuse, unexposedSpecular, unexposedStableRadiance,
            primary.diffuseHitDistance, 0.0, albedo, specularAlbedo);
}

public void runPrimary<TComposition : IComposition>() {
    uint2 pixel = DispatchRaysIndex().xy;
    uint2 extent = DispatchRaysDimensions().xy;
    ConstPtr<WorldPush> frames = (ConstPtr<WorldPush>)worldBindings.worldPushAddress;
    WorldPush frame = frames[0];
    uint pixelIndex = pixel.y * extent.x + pixel.x;

    float2 uv = (float2(pixel) + 0.5) / float2(extent);
    uint microSeed = primarySeed(pixel, frame.frameIndex ^ 0x68bc21ebu);
    float2 microJitter = (float2(
            retainedLightHash(microSeed),
            retainedLightHash(microSeed ^ 0x9e3779b9u))
            * (1.0 / 4294967296.0) - 0.5)
            * worldBindings.reconstructionMicroJitterScale;
    float2 clip = uv * 2.0 - 1.0
            + (frame.jitter + microJitter) * (2.0 / float2(extent));
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
    float3 unexposedStableRadiance = max(
            payload.primaryStableRadiance * transmittance, float3(0.0));
    float3 unexposedDiffuse = max(payload.diffuseRadiance * transmittance, float3(0.0));
    float3 unexposedSpecular = max(payload.specularRadiance * transmittance, float3(0.0));
    float3 radiance = clamp(unexposedRadiance * frame.preExposure,
            float3(0.0), float3(HALF_MAX));
    writePrimaryStablePlane<TComposition>(pixel, extent, frame, payload, radiance,
            unexposedStableRadiance, unexposedDiffuse, unexposedSpecular, ray.Direction);
}

[shader("raygeneration")]
void main<TComposition : IComposition>() {
    runPrimary<TComposition>();
}
