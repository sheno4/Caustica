module retained_indirect;

import caustica_api;
import bindings;
import world_common;
import world_minimal;
import retained_lights;
import retained_path_queue;
import path_queue_types;
import retained_trace_policy;
import stable_planes;
import nrd_signals;
import surface_bsdf;

public void runRetainedIndirect<TComposition : IComposition,
                                TTrace : IRetainedRadianceTrace>() {
    uint2 pixel = DispatchRaysIndex().xy;
    uint2 extent = DispatchRaysDimensions().xy;
    uint pixelIndex = pixel.y * extent.x + pixel.x;
    ConstPtr<WorldPush> frames = (ConstPtr<WorldPush>)worldBindings.worldPushAddress;
    WorldPush frame = frames[0];
    DevicePtr<PackedPathSegment> queue = (DevicePtr<PackedPathSegment>)worldBindings.pathQueueAddress;
    TTrace tracer = {};
    float4 normalRoughness = worldFloat4StorageImage(worldBindings.resources.normalGuide)[pixel];
    float3 diffuseBsdfEstimate =
            worldFloat4StorageImage(worldBindings.resources.albedoGuide)[pixel].xyz;
    float3 specularBsdfEstimate =
            worldFloat4StorageImage(worldBindings.resources.specularAlbedoGuide)[pixel].xyz;
    float3 indirectRadiance = float3(0.0);
    float3 diffuseRadiance = float3(0.0);
    float3 specularRadiance = float3(0.0);
    float diffuseHitDistanceSum = 0.0;
    float specularHitDistance = 0.0;
    float diffuseHitDistanceWeight = 0.0;
    uint recordIndex = pixelIndex;

    for (uint branch = 0u; branch < MAX_PATH_SEGMENTS && recordIndex != PATH_NO_NEXT; branch++) {
        PackedPathSegment initialPacked = queue[recordIndex];
        uint linkedRecord = initialPacked.nextRecord;
        RetainedPathState state = unpackRetainedPath(initialPacked);
        if (!state.valid) { recordIndex = linkedRecord; continue; }
        uint firstLobe = state.firstLobeEvent & (EVENT_DIFFUSE | EVENT_GLOSSY);
        if (firstLobe != EVENT_DIFFUSE && firstLobe != EVENT_GLOSSY) {
            recordIndex = linkedRecord;
            continue;
        }
        float3 throughput = state.throughput;

        for (uint bounce = 1u; bounce <= frame.maxBounces; bounce++) {
            RetainedPathState invalidState = state;
            invalidState.valid = false;
            queue[recordIndex] = packRetainedPath(invalidState, PATH_NO_NEXT);

            WorldPayload payload = {};
            payload.hitDistance = -1.0;
            payload.feedbackThroughput = throughput;
            payload.volumeAbsorption = state.absorption;
            payload.volumeIor = state.volumeIor;
            payload.previousPosition = state.origin;
            payload.previousBsdfPdf = state.previousBsdfPdf;
            payload.seed = state.seed;
            payload.previousNeeProposalMode = state.proposalMode;
            payload.previousNeeCandidateCount = max(state.candidateCount, 1u);
            payload.currentNeeProposalMode = NEE_AT_PROPOSAL_GLOBAL;
            payload.currentNeeCandidateCount = clamp(
                    neeAtState(worldBindings.neeAtStateAddress)[0].candidateCount, 1u, 8u);
            payload.signalHitDistance = state.signalHitDistance;
            payload.firstLobeEvent = state.firstLobeEvent;
            payload.pathFlags = WORLD_PATH_REQUEST_CONTINUATION
                    | (state.volumeActive ? WORLD_PATH_VOLUME_ACTIVE : 0u);
            payload.queueRecordIndex = recordIndex;

            RayDesc ray;
            ray.Origin = state.origin;
            ray.Direction = state.direction;
            ray.TMin = 0.001 / max(
                    neeAtState(worldBindings.neeAtStateAddress)[0].metersPerSceneUnit, 1.0e-8);
            ray.TMax = 10000.0;
            tracer.trace(payload, ray, min(bounce, 3u));

            bool missed = (payload.pathFlags & WORLD_PATH_MISS) != 0u;
            float attenuationTravel = missed ? ray.TMax : max(payload.hitDistance, 0.0);
            float3 segmentTransmittance = exp(-state.absorption * attenuationTravel);
            float3 contribution = throughput * segmentTransmittance * payload.radiance;
            indirectRadiance += contribution;
            float signalHitDistance = missed
                    ? 0.0 : state.signalHitDistance + max(payload.hitDistance, 0.0);
            if (firstLobe == EVENT_DIFFUSE) {
                diffuseRadiance += contribution;
                float contributionWeight = nrdLuminance(
                        nrdDemodulate(contribution, diffuseBsdfEstimate));
                diffuseHitDistanceSum += contributionWeight * signalHitDistance;
                diffuseHitDistanceWeight += contributionWeight;
            } else {
                specularRadiance += contribution;
                if (signalHitDistance > 0.0 && any(contribution > 0.0)) {
                    specularHitDistance = specularHitDistance > 0.0
                            ? min(specularHitDistance, signalHitDistance) : signalHitDistance;
                }
            }
            if (missed || bounce == frame.maxBounces) break;

            PackedPathSegment nextPacked = queue[recordIndex];
            RetainedPathState next = unpackRetainedPath(nextPacked);
            if (!next.valid) break;
            throughput *= segmentTransmittance * next.throughput;
            if (!any(throughput > 0.0)) break;
            if (bounce >= 3u) {
                float survival = clamp(max(throughput.x, max(throughput.y, throughput.z)), 0.02, 1.0);
                if (retainedLightRandom(next.seed) > survival) break;
                throughput /= survival;
            }
            state = next;
        }
        recordIndex = linkedRecord;
    }

    float3 accumulated = clamp(max(indirectRadiance * frame.preExposure,
            float3(0.0)), float3(0.0), float3(HALF_MAX));
    stablePlaneAddRadiance(pixel, accumulated);
    float diffuseHitDistance = diffuseHitDistanceWeight > 0.0
            ? diffuseHitDistanceSum / diffuseHitDistanceWeight : 0.0;
    nrdAddIndirectSignals(pixel, diffuseRadiance, diffuseHitDistance,
            specularRadiance, specularHitDistance, diffuseBsdfEstimate,
            specularBsdfEstimate, normalRoughness.w);
}
