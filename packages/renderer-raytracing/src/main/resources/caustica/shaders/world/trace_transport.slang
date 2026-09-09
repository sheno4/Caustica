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

public PackedPathSegment traceAdvance(PackedPathSegment path, WorldPayload hit,
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

public TraceLobes traceLobes(PackedPathSegment path, WorldPayload hit, bool continuousOnly) {
    TraceLobes lobes = {};
    float3 outgoing = -path.direction;
    float3 normal = hit.surface.shadingNormal;
    lobes.reflectedDirection = reflect(path.direction, normal);
    lobes.transmittedDirection = path.direction;
    if (!continuousOnly) {
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

public bool traceScatter(inout PackedPathSegment path, WorldPayload hit, TraceLobes lobes,
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

public float3 traceVisibility(PackedPathSegment path, WorldPayload hit,
        DirectLightSample selected, inout uint seed) {
    float3 visibility = float3(1.0);
    RayDesc ray;
    ray.Origin = hit.position + hit.geometricNormal
            * (dot(hit.geometricNormal, selected.direction) >= 0.0 ? 2.0 : -2.0) * traceEpsilon();
    ray.Direction = selected.direction;
    ray.TMin = traceEpsilon();
    ray.TMax = selected.maximumDistance;
    while (ray.TMax > ray.TMin) {
        WorldPayload shadow = {};
        retainedLightRandom(seed);
        shadow.seed = seed;
        shadow.volumeIor = path.ior;
        shadow.pathFlags = WORLD_PATH_SHADOW;
        // Any-hit only rejects coverage holes; closest-hit resolves one ordered boundary.
        TraceRay(worldAccelerationStructure(), RAY_FLAG_NONE,
                WORLD_RAY_MASK_SECONDARY, 0u, 2u, 1u, ray, shadow);
        if ((shadow.pathFlags & WORLD_PATH_MISS) != 0u) break;
        visibility *= exp(-path.absorption * shadow.hitDistance) * shadow.radiance;
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
TraceLightSelection traceSelectLight(WorldPayload hit, float3 outgoingDirection,
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

public TraceDirect traceDirect(PackedPathSegment path, WorldPayload hit, TraceLobes lobes,
        uint2 pixel, bool coherent, inout uint seed) {
    TraceDirect result = {};
    if (lobes.continuousProbability == 0.0) return result;
    TraceLightSelection selection = traceSelectLight(hit, -path.direction, pixel, coherent, seed);
    DirectLightSample selected = selection.light;
    if (!selected.valid || selection.target <= 0.0) return result;
    float3 visibility = traceVisibility(path, hit, selected, seed);
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
