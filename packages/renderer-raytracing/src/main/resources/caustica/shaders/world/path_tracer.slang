module path_tracer;
import retained_lights;
import path_queue_types;
import surface_bsdf;
import caustica_volume;
import caustica_types;
import bindings;
import world_minimal;
import world_common;
import caustica_api;
import trace_transport;
import closest_hit;
import retained_trace_policy;
import stable_planes;
import stable_plane_types;

public void runFillStablePlanes<TComposition : IComposition, TTrace : IRetainedRadianceTrace>() {
    uint2 pixel = DispatchRaysIndex().xy;
    uint2 extent = DispatchRaysDimensions().xy;
    WorldPush frame = ((ConstPtr<WorldPush>)worldBindings.worldPushAddress)[0];
    PathSegmentStorage endpoints = pathSegmentStorage(worldBindings.pathQueueAddress, STABLE_PLANE_COUNT * extent.x * extent.y);
    TTrace tracer = {};
    for (uint plane = 0u; plane < STABLE_PLANE_COUNT; plane++) {
        uint address = stablePlaneRecordIndex(pixel, extent, plane);
        if ((endpoints.loadFlags(address) & PATH_ENDPOINT_VALID) == 0u) continue;
        PackedPathSegment path = endpoints.load(address);
        float3 noisyDiffuseRadiance = stablePlaneRecords(extent).loadNoisyDiffuseRadiance(address);
        float3 noisySpecularRadiance = float3(0.0);
        float specularHitDistance = 0.0;
        bool first = true, diffusePath = false, coherent = true;
        bool previousCoherent = true;
        float previousPdf = 0.0;
        float3 previousPosition = path.origin;
        float signalDistance = 0.0, diffuseWeight = 0.0, diffuseDistanceSum = 0.0;
        uint seed = traceSeed(pixel, frame.frameIndex, plane);
        while (true) {
            WorldPayload intersection = {};
            intersection.seed = seed;
            RayDesc ray = traceRay(path);
            if (first) {
                // Restart coverage is deterministic, but fill does not consume reconstruction history.
                intersection.pathFlags = WORLD_PATH_PRIMARY;
                ray.TMin = max(traceEpsilon(), path.hitDistance * 0.99);
                ray.TMax = path.hitDistance * 1.01 + traceEpsilon();
                uint mask = path.depth == 0u ? WORLD_RAY_MASK_PRIMARY : WORLD_RAY_MASK_SECONDARY;
                TraceRay(worldAccelerationStructure(), RAY_FLAG_NONE, mask, 0u, 2u, 0u, ray, intersection);
            } else {
                tracer.trace(intersection, ray, path.depth > frame.maxBounces ? 1u : 0u);
            }
            WorldHit hit = resolveWorldHit<TComposition>(intersection, ray, previousPdf);
            bool missed = (hit.pathFlags & WORLD_PATH_MISS) != 0u;
            float distance = missed ? TRACE_MAX_DISTANCE : hit.hitDistance;
            TraceSpatialMedium spatial = traceSpatialMediumSegment<TComposition>(path, distance, pixel, !first);
            float3 segmentScattering = path.throughput * spatial.scattering;
            path.throughput *= exp(-path.absorption * distance) * spatial.transmittance;
            // Build owns restart scattering. Later scattering has a finite guide even on a sky miss.
            if (!first && any(segmentScattering > 0.0)) {
                float scatteringDistance = signalDistance + spatial.scatteringDistance;
                if (diffusePath) {
                    noisyDiffuseRadiance += segmentScattering;
                    float weight = traceLuminance(segmentScattering);
                    diffuseWeight += weight;
                    diffuseDistanceSum += weight * scatteringDistance;
                } else {
                    noisySpecularRadiance += segmentScattering;
                    if (specularHitDistance == 0.0) specularHitDistance = scatteringDistance;
                }
            }
            if (!first && !missed) signalDistance += distance;
            float3 emission = float3(0.0);
            if (!first) {
                emission = missed ? hit.radiance : hit.surface.emissionRadiance;
                if (!missed && previousPdf > 0.0 && hit.lightIndex != NEE_AT_NO_LIGHT) {
                    uint count = clamp(neeAtState(worldBindings.neeAtStateAddress)[0].candidateCount, 1u, 8u);
                    float lightPdf = neeAtReverseParallelogramPdf(worldBindings, pixel, hit.lightIndex,
                            previousPosition, hit.position, previousCoherent
                                    ? NEE_AT_PROPOSAL_SCREEN_MIXTURE : NEE_AT_PROPOSAL_GLOBAL, count);
                    emission *= previousPdf / (previousPdf + lightPdf);
                }
                emission *= path.throughput;
                if (diffusePath) {
                    noisyDiffuseRadiance += emission;
                    float weight = traceLuminance(emission);
                    diffuseWeight += weight;
                    diffuseDistanceSum += weight * signalDistance;
                } else {
                    noisySpecularRadiance += emission;
                    if (!missed && any(emission > 0.0) && specularHitDistance == 0.0)
                        specularHitDistance = signalDistance;
                }
            }
            // The last scatter's emission is consumed before stopping, preserving the NEE/MIS pair.
            if (missed || path.depth > frame.maxBounces) break;
            TraceLobes lobes = traceLobes(path, hit);
            if (first) {
                float retainedProbability;
                lobes = traceResidualLobes(lobes, path.flags, retainedProbability);
            }
            TraceDirect direct = traceDirect<TComposition>(path, hit, lobes, pixel, coherent, seed);
            float3 diffuse = path.throughput * direct.diffuse;
            float3 specular = path.throughput * direct.specular;
            if (first) {
                noisyDiffuseRadiance += diffuse;
                noisySpecularRadiance += specular;
            } else if (diffusePath) {
                noisyDiffuseRadiance += diffuse + specular;
            } else {
                noisySpecularRadiance += diffuse + specular;
            }
            float weight = traceLuminance(first ? diffuse : diffusePath ? diffuse + specular : float3(0.0));
            diffuseWeight += weight;
            diffuseDistanceSum += weight * (first ? direct.distance : signalDistance);
            if (specularHitDistance == 0.0 && any((first ? specular : diffusePath ? float3(0.0) : diffuse + specular) > 0.0))
                specularHitDistance = first ? direct.distance : signalDistance;
            previousPosition = hit.position;
            previousCoherent = coherent;
            uint event;
            if (!traceScatter(path, hit, lobes, seed, previousPdf, event)) break;
            if (first) diffusePath = (event & EVENT_DIFFUSE) != 0u;
            coherent = coherent && previousPdf == 0.0;
            first = false;
            if (path.depth >= 3u && path.depth <= frame.maxBounces) {
                float survival = clamp(max(max(path.throughput.x, path.throughput.y), path.throughput.z), 0.05, 1.0);
                if (retainedLightRandom(seed) >= survival) break;
                path.throughput /= survival;
            }
        }
        float diffuseHitDistance = diffuseWeight > 0.0 ? diffuseDistanceSum / diffuseWeight : 0.0;
        stablePlaneRecords(extent).storeNoisyDiffuseRadiance(address, noisyDiffuseRadiance);
        stablePlaneRecords(extent).storeNoisySpecularRadiance(address, noisySpecularRadiance);
        stablePlaneRecords(extent).storeDiffuseHitDistance(address, diffuseHitDistance);
        stablePlaneRecords(extent).storeSpecularHitDistance(address, specularHitDistance);
    }
    stablePlaneExportSignals(pixel, extent, frame);
}
