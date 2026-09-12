module shadow_closest_hit;

import caustica_api;
import caustica_types;
import caustica_volume;
import closest_hit;
import world_common;
import world_minimal;
import surface_bsdf;

public void runShadowClosestHit<TComposition : IComposition>(inout ShadowPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    RetainedGeometryRecord record = worldGeometryRecord();
    bool volume = (record.flags & RETAINED_GEOMETRY_HAS_VOLUME) != 0u;
    WorldInstanceHistory history = worldInstanceHistory(record, false);
    SurfaceInput input = worldSurfaceInput(attributes, record, history, false);
    TComposition.Surfaces surfaces = {};
    OpenPbrSurface material = surfaces.evaluateSurface(record.surfaceImplementation, input);
    material.geometry_thin_walled = !volume;
    if (dot(material.geometry_normal, input.geometricNormal) < 0.0) {
        material.geometry_normal = -material.geometry_normal;
    }
    SurfaceClosure surface = surfaceClosure(material, input.geometricNormal);
    payload.hitDistance = RayTCurrent();
    if (!volume) {
        payload.transmission = surfaceShadowTransmission(surface, input.outgoingDirection);
        return;
    }
    float incomingIor = payload.volumeIor;
    payload.volumeAbsorption = float3(0.0);
    payload.volumeIor = 1.0;
    payload.transmission = float3(0.0);
    if (surface.boundaryWeight <= 0.0) return;
    payload.transmission = surface.thinTransmissionColor * surface.boundaryWeight;
    TComposition.Volumes volumes = {};
    VolumeInput query = volumeInput(input, record.volumeBinding,
            input.evaluationFlags & EVALUATION_ANIMATION_ENABLED);
    if (worldHitEntering(input.objectToScene)) {
        VolumeProperties properties = volumes.evaluateVolume(record.volumeImplementation, query);
        payload.volumeAbsorption = max(properties.absorptionCoefficient, float3(0.0));
        payload.volumeIor = max(properties.indexOfRefraction, 1.0);
    } else {
        VolumeBoundaryLightingInput boundary = {};
        boundary.volume = query;
        boundary.lightDirection = normalize(WorldRayDirection());
        boundary.boundaryDistance = payload.hitDistance;
        boundary.interiorIor = incomingIor;
        payload.transmission *= max(volumes.evaluateBoundaryLighting(
                record.volumeImplementation, boundary), float3(0.0));
    }
}

[shader("closesthit")]
void main<TComposition : IComposition>(inout ShadowPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    runShadowClosestHit<TComposition>(payload, attributes);
}
