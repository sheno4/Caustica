module caustica_direct_lighting_service;

import caustica_ray_pack_api;
import caustica_ray_engine_types;

static const float3 ACESCG_LUMINANCE = float3(0.27222872, 0.67408177, 0.05368952);
static const float ENGINE_DIRECT_PI = 3.14159265359;

void engineDirectBasis(float3 n, out float3 tangent, out float3 bitangent) {
    float3 up = abs(n.z) < 0.999 ? float3(0.0, 0.0, 1.0) : float3(1.0, 0.0, 0.0);
    tangent = normalize(cross(up, n));
    bitangent = cross(n, tangent);
}

float engineDirectTarget(float3 contribution) {
    return max(0.0, dot(contribution, ACESCG_LUMINANCE));
}

public EngineDirectLightingReservoir engineBeginDirectLighting() {
    EngineDirectLightingReservoir reservoir;
    reservoir.selected.direction = float3(0.0);
    reservoir.selected.radianceOverProposalPdf = float3(0.0);
    reservoir.selected.distance = 0.0;
    reservoir.selected.sourceId = 0u;
    reservoir.selected.flags = 0u;
    reservoir.selectedTarget = 0.0;
    reservoir.weightSum = 0.0;
    reservoir.sampleCount = 0u;
    reservoir.valid = 0u;
    return reservoir;
}

// The pack describes celestial emitters; the engine owns their sampling measure and candidate weight.
public EngineDirectLightCandidate engineSampleDirectionalLight<TPack : IPackEnvironmentModel>(
        TPack pack,
        PackFrameContext frame,
        uint lightIndex,
        float selectionProbability,
        float2 random) {
    PackDirectionalLight light = pack.getDirectionalLight(frame, lightIndex);
    float cosine = lerp(1.0, cos(light.angularRadius), random.x);
    float sine = sqrt(max(0.0, 1.0 - cosine * cosine));
    float phi = 2.0 * ENGINE_DIRECT_PI * random.y;
    float3 tangent;
    float3 bitangent;
    engineDirectBasis(light.direction, tangent, bitangent);

    EngineDirectLightCandidate candidate;
    candidate.direction = normalize(light.direction * cosine
            + tangent * (cos(phi) * sine) + bitangent * (sin(phi) * sine));
    candidate.radianceOverProposalPdf = light.irradiance
            / max(selectionProbability, 1.0e-7);
    candidate.distance = 1.0e30;
    candidate.sourceId = lightIndex;
    candidate.flags = 0u;
    return candidate;
}

public float3 engineEvaluateDirectCandidate<TPack : IPackSurfaceModel>(
        TPack pack,
        PackSurfaceClosure surface,
        float3 outgoingDirection,
        float incidentIor,
        float transmittedIor,
        EngineDirectLightCandidate candidate) {
    PackBsdfQuery query;
    query.surface = surface;
    query.outgoingDirection = outgoingDirection;
    query.incomingDirection = candidate.direction;
    query.incidentIor = incidentIor;
    query.transmittedIor = transmittedIor;
    PackBsdfEvaluation bsdf = pack.evaluateBsdf(query);
    float receiverCosine = abs(dot(surface.shadingNormal, candidate.direction));
    return bsdf.value * receiverCosine * candidate.radianceOverProposalPdf;
}

// Streaming global RIS. Each candidate has already been sampled from its engine proposal, so its
// radianceOverProposalPdf carries the proposal correction. Zero-target candidates still count in M.
public void engineConsiderDirectCandidate<TPack : IPackSurfaceModel>(
        TPack pack,
        PackSurfaceClosure surface,
        float3 outgoingDirection,
        float incidentIor,
        float transmittedIor,
        EngineDirectLightCandidate candidate,
        float replacementRandom,
        inout EngineDirectLightingReservoir reservoir) {
    float3 contribution = engineEvaluateDirectCandidate(pack, surface, outgoingDirection,
            incidentIor, transmittedIor, candidate);
    float target = engineDirectTarget(contribution);
    reservoir.sampleCount += 1u;
    reservoir.weightSum += target;
    if (target > 0.0 && replacementRandom * reservoir.weightSum < target) {
        reservoir.selected = candidate;
        reservoir.selectedTarget = target;
        reservoir.valid = 1u;
    }
}

// visibility is produced by the engine shadow query after candidate selection. This keeps the pack
// independent of ray-query policy and permits the service to schedule exactly one visibility ray.
public EngineDirectLightingResult engineResolveDirectLighting<TPack : IPackSurfaceModel>(
        TPack pack,
        PackSurfaceClosure surface,
        float3 outgoingDirection,
        float incidentIor,
        float transmittedIor,
        EngineDirectLightingReservoir reservoir,
        float visibility) {
    EngineDirectLightingResult result;
    result.radiance = float3(0.0);
    result.shadowDirection = reservoir.selected.direction;
    result.shadowDistance = reservoir.selected.distance;
    result.sourceId = reservoir.selected.sourceId;
    result.valid = reservoir.valid;
    if (reservoir.valid == 0u || reservoir.sampleCount == 0u
            || reservoir.selectedTarget <= 0.0) return result;

    float3 selectedContribution = engineEvaluateDirectCandidate(pack, surface, outgoingDirection,
            incidentIor, transmittedIor, reservoir.selected);
    float normalization = reservoir.weightSum
            / (float(reservoir.sampleCount) * reservoir.selectedTarget);
    result.radiance = selectedContribution * normalization * clamp(visibility, 0.0, 1.0);
    return result;
}
