module shadow_any_hit_rahit;

import world_minimal;
import caustica_api;
import radiance_any_hit_rahit;

public void runShadowAnyHit<TComposition : IComposition>(inout ShadowPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    // Coverage holes continue traversal; closest-hit evaluates transmission at accepted surfaces.
    rejectCoverageHole<TComposition>(payload.seed, false, attributes);
}

[shader("anyhit")]
void main<TComposition : IComposition>(inout ShadowPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    runShadowAnyHit<TComposition>(payload, attributes);
}
