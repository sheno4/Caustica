module shadow_any_hit_rahit;

import world_minimal;
import caustica_api;
import radiance_any_hit_rahit;

public void runShadowAnyHit<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    // Coverage holes continue traversal; an accepted non-volume hit blocks in closest-hit.
    runRadianceAnyHit<TComposition>(payload, attributes);
}

[shader("anyhit")]
void main<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    runShadowAnyHit<TComposition>(payload, attributes);
}
