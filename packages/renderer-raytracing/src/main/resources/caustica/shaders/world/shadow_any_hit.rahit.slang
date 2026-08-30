module shadow_any_hit_rahit;

import world_minimal;
import world_common;
import caustica_api;
import caustica_coverage;
import caustica_volume;
import caustica_types;
import caustica_surface;

VolumeInput shadowVolumeInput(RetainedGeometryRecord record) {
    ConstPtr<WorldPush> frame = (ConstPtr<WorldPush>)worldBindings.worldPushAddress;
    VolumeInput input = {};
    input.shaderRoot = worldShaderRoot();
    input.bindingData = record.volumeBinding;
    input.instanceData = record.instanceData;
    input.boundaryPosition = WorldRayOrigin() + WorldRayDirection() * RayTCurrent();
    input.proceduralPosition = input.boundaryPosition + frame[0].proceduralDomainOffset;
    input.metersPerSceneUnit = neeAtState(worldBindings.neeAtStateAddress)[0].metersPerSceneUnit;
    input.time = frame[0].time;
    input.previousTime = frame[0].previousTime;
    input.evaluationFlags = (frame[0].flags & 16u) != 0u ? EVALUATION_ANIMATION_ENABLED : 0u;
    return input;
}

public void runShadowAnyHit<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    TComposition.Coverages coverages = {};
    var record = worldGeometryRecord();
    if ((record.flags & RETAINED_GEOMETRY_HAS_VOLUME) != 0u) {
        TComposition.Surfaces surfaces = {};
        OpenPbrSurface surface = surfaces.evaluateSurface(record.surfaceImplementation,
                worldSurfaceInput(attributes));
        float transmission = clamp(surface.transmission_weight, 0.0, 1.0);
        if (transmission <= 0.0) {
            payload.radiance = float3(0.0);
            return;
        }
        payload.radiance *= transmission
                * clamp(surface.transmission_color, float3(0.0), float3(1.0));
        float segmentLength = max(RayTCurrent() - payload.hitDistance, 0.0);
        payload.radiance *= exp(-max(payload.volumeAbsorption, float3(0.0)) * segmentLength);
        payload.hitDistance = RayTCurrent();
        TComposition.Volumes volumes = {};
        VolumeInput volumeInput = shadowVolumeInput(record);
        if (worldHitEntering()) {
            VolumeProperties volume = volumes.evaluateVolume(record.volumeImplementation, volumeInput);
            payload.volumeAbsorption = max(volume.absorptionCoefficient, float3(0.0));
            payload.volumeIor = max(volume.indexOfRefraction, 1.0);
            payload.pathFlags |= WORLD_PATH_VOLUME_ACTIVE;
        } else {
            VolumeBoundaryLightingInput boundary = {};
            boundary.volume = volumeInput;
            boundary.lightDirection = normalize(WorldRayDirection());
            boundary.boundaryDistance = segmentLength;
            boundary.interiorIor = payload.volumeIor;
            payload.radiance *= max(volumes.evaluateBoundaryLighting(
                    record.volumeImplementation, boundary), float3(0.0));
            payload.volumeAbsorption = float3(0.0);
            payload.volumeIor = 1.0;
            payload.pathFlags &= ~WORLD_PATH_VOLUME_ACTIVE;
        }
        IgnoreHit();
        return;
    }
    float coverage = clamp(coverages.evaluateCoverage(record.coverageImplementation,
            worldCoverageInput(attributes)), 0.0, 1.0);
    if ((record.flags & RETAINED_GEOMETRY_STOCHASTIC) != 0u) {
        payload.radiance *= 1.0 - coverage;
        if (!any(payload.radiance > 0.0)) return;
        IgnoreHit();
    } else if (coverage < record.alphaCutoff) {
        IgnoreHit();
    } else {
        payload.radiance = float3(0.0);
    }
}

[shader("anyhit")]
void main<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    runShadowAnyHit<TComposition>(payload, attributes);
}
