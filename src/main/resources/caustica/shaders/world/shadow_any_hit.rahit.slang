module shadow_any_hit_rahit;

import world_minimal;
import caustica_api;
import caustica_coverage;

public void runShadowAnyHit<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    TComposition.Coverages coverages = {};
    if (coverages.evaluateCoverage(0u, worldCoverageInput(attributes)) < 0.5) {
        IgnoreHit();
    }
}

[shader("anyhit")]
void main<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    runShadowAnyHit<TComposition>(payload, attributes);
}
