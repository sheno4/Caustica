module closest_hit;

import world_minimal;
import caustica_api;
import caustica_surface;
import caustica_types;

public void runClosestHit<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    TComposition.Surfaces surfaces = {};
    OpenPbrSurface surface = surfaces.evaluateSurface(0u, worldSurfaceInput(attributes));
    float3 emission = max(surface.emission_color, float3(0.0))
            * max(surface.emission_luminance, 0.0);
    payload.radiance = max(surface.base_color, float3(0.0)) + emission;
    payload.hitDistance = RayTCurrent();
}

[shader("closesthit")]
void main<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    runClosestHit<TComposition>(payload, attributes);
}
