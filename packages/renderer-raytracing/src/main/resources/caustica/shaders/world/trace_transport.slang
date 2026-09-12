module trace_transport;
import caustica_types;
import bindings;
import world_common;
import caustica_api;
import caustica_volume;
import world_minimal;
import retained_lights;
import surface_bsdf;
import path_queue_types;
import closest_hit;
import caustica_coverage;

public static const float TRACE_MAX_DISTANCE = 10000.0;

public float traceLuminance(float3 value) {
    return dot(max(value, float3(0.0)), ACESCG_LUMA);
}

public float traceEpsilon() {
    return 0.001 / neeAtState(worldBindings.neeAtStateAddress)[0].metersPerSceneUnit;
}

public uint traceSeed(uint2 pixel, uint frame, uint plane) {
    return neeAtHash(pixel.x ^ (pixel.y * 0x9e3779b9u)
            ^ (frame * 0x85ebca6bu) ^ (plane * 0xc2b2ae35u)) | 1u;
}

public RayDesc traceRay(PackedPathSegment path) {
    RayDesc ray;
    ray.Origin = path.origin;
    ray.Direction = path.direction;
    ray.TMin = traceEpsilon();
    ray.TMax = TRACE_MAX_DISTANCE;
    return ray;
}

public PackedPathSegment traceAdvance(PackedPathSegment path, WorldHit hit,
        float3 direction, float3 weight, bool crossesBoundary) {
    path.origin = hit.position + hit.geometricNormal
            * (dot(hit.geometricNormal, direction) >= 0.0 ? 2.0 : -2.0) * traceEpsilon();
    path.direction = normalize(direction);
    path.throughput *= weight;
    path.depth++;
    path.hitDistance = 0.0;
    path.flags = PATH_ENDPOINT_VALID | (path.flags & PATH_ENDPOINT_VOLUME);
    if (crossesBoundary) {
        path.absorption = hit.volumeAbsorption;
        path.ior = hit.volumeIor;
        path.flags = PATH_ENDPOINT_VALID
                | ((hit.pathFlags & WORLD_PATH_VOLUME_ACTIVE) != 0u ? PATH_ENDPOINT_VOLUME : 0u);
    }
    return path;
}

public struct TraceLobes {
    public float3 reflection;
    public float3 transmission;
    public float3 reflectedDirection;
    public float3 transmittedDirection;
    public float continuousProbability;
    public float reflectionProbability;
    public float transmissionProbability;
    public bool volumeTransmission;
};

float traceFresnel(float cosine, float etaI, float etaT) {
    float sinT2 = (etaI * etaI) / (etaT * etaT) * max(0.0, 1.0 - cosine * cosine);
    if (sinT2 >= 1.0) return 1.0;
    float cosT = sqrt(1.0 - sinT2);
    float rs = (etaI * cosine - etaT * cosT) / (etaI * cosine + etaT * cosT);
    float rp = (etaT * cosine - etaI * cosT) / (etaT * cosine + etaI * cosT);
    return 0.5 * (rs * rs + rp * rp);
}

public TraceLobes traceLobes(PackedPathSegment path, WorldHit hit) {
    TraceLobes lobes = {};
    float3 outgoing = -path.direction;
    float3 normal = hit.surface.shadingNormal;
    lobes.reflectedDirection = reflect(path.direction, normal);
    lobes.transmittedDirection = path.direction;
    surfaceDeltaLobes(hit.surface, outgoing, lobes.reflection, lobes.transmission);
    if (hit.surface.boundaryWeight > 0.0) {
        float cosine = max(dot(outgoing, normal), 0.0);
        float fresnel = traceFresnel(cosine, path.ior, hit.volumeIor);
        lobes.reflection += hit.surface.boundaryWeight * fresnel;
        lobes.transmittedDirection = refract(path.direction, normal, path.ior / hit.volumeIor);
        lobes.transmission += hit.surface.boundaryWeight * (1.0 - fresnel)
                * hit.surface.thinTransmissionColor
                * ((path.ior * path.ior) / (hit.volumeIor * hit.volumeIor));
        lobes.volumeTransmission = true;
    }
    float3 diffuse, specular;
    surfaceDenoiserEstimates(hit.surface, outgoing, diffuse, specular);
    float c = max(max(diffuse.x, diffuse.y), diffuse.z)
            + max(max(specular.x, specular.y), specular.z);
    float r = max(max(lobes.reflection.x, lobes.reflection.y), lobes.reflection.z);
    float t = max(max(lobes.transmission.x, lobes.transmission.y), lobes.transmission.z);
    float total = c + r + t;
    if (total > 0.0) {
        lobes.continuousProbability = c / total;
        lobes.reflectionProbability = r / total;
        lobes.transmissionProbability = t / total;
    }
    return lobes;
}

// Keep RGB lobe weights intact while conditioning sampling on this endpoint's owned transport.
// The pre-normalization share is only a guide-dominance estimate, never a radiance multiplier.
public TraceLobes traceResidualLobes(TraceLobes lobes, uint endpointFlags, out float retainedProbability) {
    retainedProbability = 1.0;
    if ((endpointFlags & (PATH_ENDPOINT_DELEGATED_REFLECTION | PATH_ENDPOINT_DELEGATED_TRANSMISSION)) == 0u)
        return lobes;
    if ((endpointFlags & PATH_ENDPOINT_DELEGATED_REFLECTION) != 0u) {
        lobes.reflection = float3(0.0);
        lobes.reflectionProbability = 0.0;
    }
    if ((endpointFlags & PATH_ENDPOINT_DELEGATED_TRANSMISSION) != 0u) {
        lobes.transmission = float3(0.0);
        lobes.transmissionProbability = 0.0;
    }
    retainedProbability = lobes.continuousProbability + lobes.reflectionProbability + lobes.transmissionProbability;
    if (retainedProbability > 0.0) {
        lobes.continuousProbability /= retainedProbability;
        lobes.reflectionProbability /= retainedProbability;
        lobes.transmissionProbability /= retainedProbability;
    }
    return lobes;
}

public bool traceScatter(inout PackedPathSegment path, WorldHit hit, TraceLobes lobes,
        inout uint seed, out float pdf, out uint event) {
    pdf = 0.0;
    event = EVENT_NONE;
    float select = retainedLightRandom(seed);
    if (select < lobes.reflectionProbability) {
        path = traceAdvance(path, hit, lobes.reflectedDirection,
                lobes.reflection / lobes.reflectionProbability, false);
        event = EVENT_GLOSSY | EVENT_REFLECTION;
        return true;
    }
    if (select < lobes.reflectionProbability + lobes.transmissionProbability) {
        path = traceAdvance(path, hit, lobes.transmittedDirection,
                lobes.transmission / lobes.transmissionProbability, lobes.volumeTransmission);
        event = EVENT_GLOSSY | EVENT_TRANSMISSION;
        return true;
    }
    if (lobes.continuousProbability == 0.0) return false;
    BsdfSampleQuery query;
    query.surface = hit.surface;
    query.outgoingDirection = -path.direction;
    query.random = float3(retainedLightRandom(seed), retainedLightRandom(seed), retainedLightRandom(seed));
    BsdfSample sample = sampleBsdf(query);
    if (sample.pdf <= 0.0 || !any(sample.throughputWeight > 0.0)) return false;
    pdf = sample.pdf * lobes.continuousProbability;
    event = sample.eventFlags;
    path = traceAdvance(path, hit, sample.incomingDirection,
            sample.throughputWeight / lobes.continuousProbability, false);
    return true;
}

public ShadowPayload traceShadowQuery<TComposition : IComposition>(RayDesc ray,
        uint seed, float3 absorption, float ior) {
    ShadowPayload shadow = {};
    shadow.seed = seed;
    shadow.volumeAbsorption = absorption;
    shadow.volumeIor = ior;
    RayQuery<RAY_FLAG_FORCE_NON_OPAQUE> query;
    query.TraceRayInline(worldAccelerationStructure(), RAY_FLAG_NONE,
            WORLD_RAY_MASK_SECONDARY, ray);
    while (query.Proceed()) {
        uint instanceBase = query.CandidateInstanceID();
        uint geometryIndex = query.CandidateGeometryIndex();
        uint primitiveIndex = query.CandidatePrimitiveIndex();
        RetainedGeometryRecord record = ((ConstPtr<RetainedGeometryRecord>)worldBindings.geometryTableAddress)
                [instanceBase + geometryIndex];
        // A certified blocker has full coverage and zero transmission across its entire revision.
        if ((record.flags & RETAINED_GEOMETRY_GUARANTEED_SHADOW_BLOCKER) != 0u) {
            shadow.hitDistance = query.CandidateTriangleRayT();
            query.Abort();
            return shadow;
        }
        if ((record.flags & RETAINED_GEOMETRY_HAS_VOLUME) == 0u) {
            CoverageInput input = {};
            input.shaderRoot = worldShaderRoot();
            input.bindingData = record.surfaceBinding;
            input.instanceData = worldInstanceRecord(record).instanceData;
            input.primitiveIndex = primitiveIndex;
            input.barycentrics = query.CandidateTriangleBarycentrics();
            TComposition.Coverages coverages = {};
            float coverage = clamp(coverages.evaluateCoverage(record.coverageImplementation, input), 0.0, 1.0);
            bool stochastic = (record.flags & RETAINED_GEOMETRY_STOCHASTIC) != 0u;
            if (stochastic ? worldCoverageRandom(seed, instanceBase, geometryIndex, primitiveIndex) >= coverage
                    : coverage < record.alphaCutoff) continue;
        }
        // Committing narrows the search distance; traversal still finds the nearest accepted surface.
        query.CommitNonOpaqueTriangleHit();
    }
    if (query.CommittedStatus() == COMMITTED_NOTHING) {
        shadow.hitDistance = -1.0;
        return shadow;
    }
    RetainedGeometryRecord record = ((ConstPtr<RetainedGeometryRecord>)worldBindings.geometryTableAddress)
            [query.CommittedInstanceID() + query.CommittedGeometryIndex()];
    uint primitiveIndex = query.CommittedPrimitiveIndex();
    shadow.hitDistance = query.CommittedRayT();
    WorldInstanceHistory history = worldInstanceHistory(record, false);
    uint3 indices = worldTriangleIndices(record, primitiveIndex);
    float3 p0 = worldVertex(history.current, indices.x);
    float3 edgeU = worldVertex(history.current, indices.y) - p0;
    float3 edgeV = worldVertex(history.current, indices.z) - p0;
    SurfaceInput input = worldSurfaceInput(query.CommittedTriangleBarycentrics(), primitiveIndex,
            ray.Origin + ray.Direction * shadow.hitDistance, ray.Direction, edgeU, edgeV,
            record, history, false);
    bool volume = (record.flags & RETAINED_GEOMETRY_HAS_VOLUME) != 0u;
    TComposition.Surfaces surfaces = {};
    OpenPbrSurface material = surfaces.evaluateSurface(record.surfaceImplementation, input);
    material.geometry_thin_walled = !volume;
    if (dot(material.geometry_normal, input.geometricNormal) < 0.0)
        material.geometry_normal = -material.geometry_normal;
    SurfaceClosure surface = surfaceClosure(material, input.geometricNormal);
    if (!volume) {
        shadow.transmission = surfaceShadowTransmission(surface, input.outgoingDirection);
        return shadow;
    }
    float incomingIor = shadow.volumeIor;
    shadow.volumeAbsorption = float3(0.0);
    shadow.volumeIor = 1.0;
    if (surface.boundaryWeight <= 0.0) return shadow;
    shadow.transmission = surface.thinTransmissionColor * surface.boundaryWeight;
    TComposition.Volumes volumes = {};
    VolumeInput volumeQuery = volumeInput(input, record.volumeBinding,
            input.evaluationFlags & EVALUATION_ANIMATION_ENABLED);
    if (dot(worldTriangleNormal(input.objectToScene, edgeU, edgeV), ray.Direction) < 0.0) {
        VolumeProperties properties = volumes.evaluateVolume(record.volumeImplementation, volumeQuery);
        shadow.volumeAbsorption = max(properties.absorptionCoefficient, float3(0.0));
        shadow.volumeIor = max(properties.indexOfRefraction, 1.0);
    } else {
        VolumeBoundaryLightingInput boundary = {};
        boundary.volume = volumeQuery;
        boundary.lightDirection = normalize(ray.Direction);
        boundary.boundaryDistance = shadow.hitDistance;
        boundary.interiorIor = incomingIor;
        shadow.transmission *= max(volumes.evaluateBoundaryLighting(
                record.volumeImplementation, boundary), float3(0.0));
    }
    return shadow;
}

public float3 traceVisibility<TComposition : IComposition>(PackedPathSegment path, WorldHit hit,
        DirectLightSample selected, inout uint seed) {
    float3 visibility = float3(1.0);
    RayDesc ray;
    ray.Origin = hit.position + hit.geometricNormal
            * (dot(hit.geometricNormal, selected.direction) >= 0.0 ? 2.0 : -2.0) * traceEpsilon();
    ray.Direction = selected.direction;
    ray.TMin = traceEpsilon();
    ray.TMax = selected.maximumDistance;
    while (ray.TMax > ray.TMin) {
        retainedLightRandom(seed);
        ShadowPayload shadow = traceShadowQuery<TComposition>(ray, seed, path.absorption, path.ior);
        if (shadow.hitDistance < 0.0) break;
        visibility *= exp(-path.absorption * shadow.hitDistance) * shadow.transmission;
        if (!any(visibility > 0.0)) return float3(0.0);
        path.absorption = shadow.volumeAbsorption;
        path.ior = shadow.volumeIor;
        float advance = min(traceEpsilon(), ray.TMax - shadow.hitDistance);
        visibility *= exp(-path.absorption * advance);
        ray.Origin += ray.Direction * (shadow.hitDistance + advance);
        ray.TMax -= shadow.hitDistance + advance;
    }
    return visibility * exp(-path.absorption * ray.TMax);
}

public struct TraceDirect {
    public float3 diffuse;
    public float3 specular;
    public float distance;
};

struct TraceLightSelection {
    DirectLightSample light;
    float weightSum;
    float target;
    uint candidateCount;
};

// The reservoir keeps one candidate; its sum and target normalize that candidate's contribution.
TraceLightSelection traceSelectLight(WorldHit hit, float3 outgoingDirection,
        uint2 pixel, bool coherent, inout uint seed) {
    uint count;
    DirectLightSample selected = invalidDirectLight();
    float sum = 0.0, selectedTarget = 0.0;
    BsdfQuery query;
    query.surface = hit.surface;
    query.outgoingDirection = outgoingDirection;
    {
        NeeAtState state = neeAtState(worldBindings.neeAtStateAddress)[0];
        count = clamp(state.candidateCount, 1u, 8u);
        RetainedLightSamplingContext context = retainedLightSamplingContext(state, pixel, count, coherent);
        for (uint i = 0u; i < count; i++) {
            DirectLightSample candidate = sampleRetainedLight(context, hit.position, i, seed);
            if (!candidate.valid) continue;
            query.incomingDirection = candidate.direction;
            float target = max(max(candidate.radianceOverShapePdf.x,
                    candidate.radianceOverShapePdf.y), candidate.radianceOverShapePdf.z)
                    * evaluateBsdfPdf(query);
            float weight = target / candidate.proposalPdf;
            sum += weight;
            if (weight > 0.0 && retainedLightRandom(seed) * sum < weight) {
                selected = candidate;
                selectedTarget = target;
            }
        }
    }
    return { selected, sum, selectedTarget, count };
}

public TraceDirect traceDirect<TComposition : IComposition>(PackedPathSegment path, WorldHit hit, TraceLobes lobes,
        uint2 pixel, bool coherent, inout uint seed) {
    TraceDirect result = {};
    if (lobes.continuousProbability == 0.0) return result;
    TraceLightSelection selection = traceSelectLight(hit, -path.direction, pixel, coherent, seed);
    DirectLightSample selected = selection.light;
    if (!selected.valid || selection.target <= 0.0) return result;
    float3 visibility = traceVisibility<TComposition>(path, hit, selected, seed);
    if (!any(visibility > 0.0)) return result;
    BsdfQuery query;
    query.surface = hit.surface;
    query.outgoingDirection = -path.direction;
    query.incomingDirection = selected.direction;
    BsdfEvaluation evaluation = evaluateBsdf(query);
    float3 factor = selected.radianceOverShapePdf * abs(dot(hit.surface.shadingNormal,
            selected.direction)) * visibility * (selection.weightSum / (float(selection.candidateCount) * selection.target));
    result.diffuse = evaluation.diffuseValue * factor;
    result.specular = evaluation.specularValue * factor;
    if (selected.linkedEmitter && selected.shapePdf > 0.0) {
        float pdf = neeAtLightMisPdf(selected.proposalPdf, selected.shapePdf, selection.candidateCount);
        result.diffuse *= pdf / (pdf + evaluation.diffusePdf * lobes.continuousProbability);
        result.specular *= pdf / (pdf + evaluation.specularPdf * lobes.continuousProbability);
    }
    result.distance = selected.signalDistance;
    neeAtWriteFeedback(worldBindings, pixel, selected.lightIndex,
            traceLuminance(path.throughput * (result.diffuse + result.specular)), coherent);
    return result;
}

public PackedPathSegment traceCamera<TComposition : IComposition>(uint2 pixel,
        uint2 extent, WorldPush frame) {
    PackedPathSegment path = {};
    float2 clip = (float2(pixel) + 0.5 + frame.jitter) * (2.0 / float2(extent)) - 1.0;
    float4 point = mul(frame.invViewProj, float4(clip, 0.0, 1.0));
    path.origin = frame.camOffset;
    path.direction = normalize(point.xyz / point.w);
    path.throughput = float3(1.0);
    path.ior = 1.0;
    path.flags = PATH_ENDPOINT_VALID;
    if (worldBindings.initialVolumeActive != 0u) {
        TComposition.Volumes volumes = {};
        VolumeInput input = {};
        input.shaderRoot = worldShaderRoot();
        input.bindingData = worldBindings.initialVolumeBinding;
        input.instanceData = worldBindings.initialVolumeInstance;
        input.boundaryPosition = path.origin;
        input.proceduralPosition = path.origin + frame.proceduralDomainOffset;
        input.metersPerSceneUnit = neeAtState(worldBindings.neeAtStateAddress)[0].metersPerSceneUnit;
        input.time = frame.time;
        input.previousTime = frame.previousTime;
        input.evaluationFlags = (frame.flags & 16u) != 0u ? EVALUATION_ANIMATION_ENABLED : 0u;
        VolumeProperties properties = volumes.evaluateVolume(worldBindings.initialVolumeImplementation, input);
        path.absorption = max(properties.absorptionCoefficient, float3(0.0));
        path.ior = max(properties.indexOfRefraction, 1.0);
        path.flags |= PATH_ENDPOINT_VOLUME;
    }
    return path;
}
