module radiance_any_hit_rahit;

import world_minimal;
import caustica_api;
import caustica_coverage;

public void runRadianceAnyHit<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    TComposition.Coverages coverages = {};
    var record = worldGeometryRecord();
    if ((record.flags & 2u) != 0u) return;
    if (coverages.evaluateCoverage(record.coverageImplementation,
            worldCoverageInput(attributes)) < record.alphaCutoff) {
        IgnoreHit();
    }
}

[shader("anyhit")]
void main<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    runRadianceAnyHit<TComposition>(payload, attributes);
}
