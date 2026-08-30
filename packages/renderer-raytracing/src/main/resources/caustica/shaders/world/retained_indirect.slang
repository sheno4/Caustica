module retained_indirect;

import caustica_api;
import bindings;
import world_common;
import world_minimal;
import retained_lights;
import retained_path_queue;
import path_queue_types;
import retained_trace_policy;

public void runRetainedIndirect<TComposition : IComposition,
                                TTrace : IRetainedRadianceTrace>() {
    uint2 pixel = DispatchRaysIndex().xy;
    uint2 extent = DispatchRaysDimensions().xy;
    uint pixelIndex = pixel.y * extent.x + pixel.x;
    ConstPtr<WorldPush> frames = (ConstPtr<WorldPush>)worldBindings.worldPushAddress;
    WorldPush frame = frames[0];
    DevicePtr<PackedPathSegment> queue = (DevicePtr<PackedPathSegment>)worldBindings.pathQueueAddress;
    TTrace tracer = {};
    float3 indirectRadiance = float3(0.0);
    uint recordIndex = pixelIndex;

    for (uint branch = 0u; branch < MAX_PATH_SEGMENTS && recordIndex != PATH_NO_NEXT; branch++) {
        PackedPathSegment initialPacked = queue[recordIndex];
        uint linkedRecord = initialPacked.nextRecord;
        RetainedPathState state = unpackRetainedPath(initialPacked);
        if (!state.valid) { recordIndex = linkedRecord; continue; }
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

            float travel = payload.hitDistance >= 0.0 ? payload.hitDistance : ray.TMax;
            float3 segmentTransmittance = exp(-state.absorption * max(travel, 0.0));
            indirectRadiance += throughput * segmentTransmittance * payload.radiance;
            if ((payload.pathFlags & WORLD_PATH_MISS) != 0u || bounce == frame.maxBounces) break;

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

    RWTexture2D<float4> output = worldFloat4StorageImage(worldBindings.resources.outputImage);
    float4 primary = output[pixel];
    float3 accumulated = clamp(max(primary.xyz + indirectRadiance * frame.preExposure,
            float3(0.0)), float3(0.0), float3(HALF_MAX));
    output[pixel] = float4(accumulated, primary.w);
}
