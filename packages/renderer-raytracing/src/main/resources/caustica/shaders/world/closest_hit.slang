module closest_hit;
import retained_lights;
import path_queue_types;
import caustica_types;
import bindings;
import world_common;
import world_minimal;
import caustica_api;
import caustica_surface;
import caustica_volume;
import surface_bsdf;

VolumeInput volumeInput(SurfaceInput surface, uint64_t bindingData, uint evaluationFlags) {
    VolumeInput query = {};
    query.shaderRoot = surface.shaderRoot;
    query.bindingData = bindingData;
    query.instanceData = surface.instanceData;
    query.boundaryPosition = surface.position;
    query.proceduralPosition = surface.proceduralPosition;
    query.metersPerSceneUnit = surface.metersPerSceneUnit;
    query.time = surface.time;
    query.previousTime = surface.previousTime;
    query.evaluationFlags = evaluationFlags;
    return query;
}

void resolveShadowBoundary<TComposition : IComposition>(inout WorldPayload payload,
        RetainedGeometryRecord record, SurfaceInput input) {
    float incomingIor = payload.volumeIor;
    payload.hitDistance = RayTCurrent();
    payload.pathFlags &= ~(WORLD_PATH_MISS | WORLD_PATH_VOLUME_ACTIVE);
    payload.volumeAbsorption = float3(0.0);
    payload.volumeIor = 1.0;
    payload.radiance = float3(0.0);
    if (payload.surface.boundaryWeight <= 0.0) return;
    payload.radiance = payload.surface.thinTransmissionColor * payload.surface.boundaryWeight;
    TComposition.Volumes volumes = {};
    VolumeInput query = volumeInput(input, record.volumeBinding,
            input.evaluationFlags & EVALUATION_ANIMATION_ENABLED);
    if (worldHitEntering(input.objectToScene)) {
        VolumeProperties properties = volumes.evaluateVolume(record.volumeImplementation, query);
        payload.volumeAbsorption = max(properties.absorptionCoefficient, float3(0.0));
        payload.volumeIor = max(properties.indexOfRefraction, 1.0);
        payload.pathFlags |= WORLD_PATH_VOLUME_ACTIVE;
    } else {
        VolumeBoundaryLightingInput boundary = {};
        boundary.volume = query;
        boundary.lightDirection = normalize(WorldRayDirection());
        boundary.boundaryDistance = payload.hitDistance;
        boundary.interiorIor = incomingIor;
        payload.radiance *= max(volumes.evaluateBoundaryLighting(
                record.volumeImplementation, boundary), float3(0.0));
    }
}

public void runClosestHit<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    RetainedGeometryRecord record = worldGeometryRecord();
    bool volume = (record.flags & RETAINED_GEOMETRY_HAS_VOLUME) != 0u;
    // Coverage has already accepted this hit. An opaque shadow blocker needs no material evaluation.
    if ((payload.pathFlags & WORLD_PATH_SHADOW) != 0u && !volume) {
        payload.hitDistance = RayTCurrent();
        payload.pathFlags &= ~WORLD_PATH_MISS;
        payload.radiance = float3(0.0);
        return;
    }
    WorldInstanceHistory history = worldInstanceHistory(record);
    SurfaceInput input = worldSurfaceInput(attributes, record, history);
    TComposition.Surfaces surfaces = {};
    OpenPbrSurface material = surfaces.evaluateSurface(record.surfaceImplementation, input);
    material.geometry_thin_walled = !volume;
    payload.surface = surfaceClosure(material, input.geometricNormal);
    if ((payload.pathFlags & WORLD_PATH_SHADOW) != 0u) {
        resolveShadowBoundary<TComposition>(payload, record, input);
        return;
    }
    payload.position = input.position;
    payload.previousPosition = worldPreviousHitPosition(attributes, record, history);
    payload.geometricNormal = input.geometricNormal;
    payload.previousNormal = any(material.previous_geometry_normal != 0.0)
            ? normalize(material.previous_geometry_normal) : input.previousGeometricNormal;
    payload.hitDistance = RayTCurrent();
    payload.lightIndex = worldEmitterLightIndex(record);
    payload.pathFlags &= ~(WORLD_PATH_MISS | WORLD_PATH_VOLUME_ACTIVE);
    payload.volumeAbsorption = float3(0.0);
    payload.volumeIor = 1.0;
    if (volume && worldHitEntering(input.objectToScene)) {
        TComposition.Volumes volumes = {};
        VolumeInput query = volumeInput(input, record.volumeBinding, input.evaluationFlags);
        VolumeProperties properties = volumes.evaluateVolume(record.volumeImplementation, query);
        payload.volumeAbsorption = max(properties.absorptionCoefficient, float3(0.0));
        payload.volumeIor = max(properties.indexOfRefraction, 1.0);
        payload.pathFlags |= WORLD_PATH_VOLUME_ACTIVE;
    }
}


[shader("closesthit")]
void main<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    runClosestHit<TComposition>(payload, attributes);
}
