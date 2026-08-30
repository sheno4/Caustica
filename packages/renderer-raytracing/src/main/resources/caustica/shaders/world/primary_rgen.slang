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

float2 primarySpecularMotion<TComposition : IComposition>(uint2 pixel, uint2 extent,
        WorldPush frame, WorldPayload primary, float3 primaryDirection) {
    if ((primary.pathFlags & WORLD_PATH_MISS) != 0u || primary.hitDistance < 0.0) return float2(0.0);
    float3 specularAlbedo = retainedUnpackRgb9e5(primary.guideSpecularAlbedo);
    if (primary.roughness >= 0.35
            || max(specularAlbedo.x, max(specularAlbedo.y, specularAlbedo.z)) <= 0.02) {
        return float2(0.0);
    }
    float3 normal = unpackNormalOct(primary.guideNormal);
    float3 reflected = normalize(reflect(primaryDirection, normal));
    float epsilon = 0.002 / max(
            neeAtState(worldBindings.neeAtStateAddress)[0].metersPerSceneUnit, 1.0e-8);
    RayDesc ray;
    ray.Origin = primary.hitPosition + (dot(normal, reflected) >= 0.0 ? normal : -normal) * epsilon;
    ray.Direction = reflected;
    ray.TMin = 0.5 * epsilon;
    ray.TMax = 10000.0;
    WorldPayload secondary = {};
    secondary.hitDistance = -1.0;
    secondary.seed = primary.seed ^ 0xa511e9b3u;
    secondary.previousNeeProposalMode = NEE_AT_PROPOSAL_GLOBAL;
    secondary.previousNeeCandidateCount = 1u;
    secondary.pathFlags = WORLD_PATH_GUIDE;
    TraceRay(worldAccelerationStructure(worldBindings), RAY_FLAG_NONE, WORLD_RAY_MASK_SECONDARY,
            0u, 2u, 1u, ray, secondary);
    if ((secondary.pathFlags & WORLD_PATH_MISS) != 0u || secondary.hitDistance < 0.0) {
        return float2(0.0);
    }
    float4 currentClip = mul(frame.curViewProj,
            float4(secondary.hitPosition - frame.camOffset, 1.0));
    float4 previousClip = mul(frame.prevViewProj,
            float4(secondary.previousHitPosition - frame.camOffset + frame.camDelta, 1.0));
    if (currentClip.w <= 0.0 || previousClip.w <= 0.0) return float2(0.0);
    return (previousClip.xy / previousClip.w - currentClip.xy / currentClip.w)
            * 0.5 * float2(extent);
}

void writePrimaryGuides<TComposition : IComposition>(uint2 pixel, uint2 extent,
        WorldPush frame, WorldPayload payload, float3 primaryDirection) {
    float3 normal = float3(0.0);
    float3 albedo = float3(0.0);
    float3 specularAlbedo = float3(0.0);
    float depth = 0.0;
    float2 motion = float2(0.0);
    float2 specularMotion = float2(0.0);
    if ((payload.pathFlags & WORLD_PATH_MISS) == 0u && payload.hitDistance >= 0.0) {
        normal = unpackNormalOct(payload.guideNormal);
        albedo = retainedUnpackRgb9e5(payload.guideAlbedo);
        specularAlbedo = retainedUnpackRgb9e5(payload.guideSpecularAlbedo);
        float3 currentCameraRelative = payload.hitPosition - frame.camOffset;
        float4 currentClip = mul(frame.curViewProj, float4(currentCameraRelative, 1.0));
        if (currentClip.w > 0.0) depth = currentClip.w;
        float3 previousCameraRelative = payload.previousHitPosition - frame.camOffset + frame.camDelta;
        float4 previousClip = mul(frame.prevViewProj, float4(previousCameraRelative, 1.0));
        if (previousClip.w > 0.0 && currentClip.w > 0.0) {
            motion = (previousClip.xy / previousClip.w - currentClip.xy / currentClip.w)
                    * 0.5 * float2(extent);
        }
        specularMotion = primarySpecularMotion<TComposition>(
                pixel, extent, frame, payload, primaryDirection);
    }
    worldFloat4StorageImage(worldBindings.resources.normalGuide)[pixel] =
            float4(normal, payload.roughness);
    float emissive = (payload.pathFlags & WORLD_PATH_EMISSIVE) != 0u ? 1.0 : 0.0;
    worldFloat4StorageImage(worldBindings.resources.albedoGuide)[pixel] = float4(albedo, emissive);
    worldFloatStorageImage(worldBindings.resources.depthGuide)[pixel] = depth;
    worldFloat2StorageImage(worldBindings.resources.motionGuide)[pixel] = motion;
    worldFloat4StorageImage(worldBindings.resources.specularAlbedoGuide)[pixel] =
            float4(specularAlbedo, 1.0);
    worldFloat2StorageImage(worldBindings.resources.specularMotionGuide)[pixel] = specularMotion;
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
    float3 radiance = clamp(max(payload.radiance * transmittance, float3(0.0))
            * frame.preExposure, float3(0.0), float3(HALF_MAX));
    worldFloat4StorageImage(worldBindings.resources.outputImage)[pixel] = float4(radiance, 1.0);
    writePrimaryGuides<TComposition>(pixel, extent, frame, payload, ray.Direction);
}

[shader("raygeneration")]
void main<TComposition : IComposition>() {
    runPrimary<TComposition>();
}
