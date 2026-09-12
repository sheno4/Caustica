module radiance_any_hit_rahit;

import world_minimal;
import world_common;
import caustica_api;
import caustica_coverage;

public void rejectCoverageHole<TComposition : IComposition>(uint seed, bool deterministic,
        BuiltInTriangleIntersectionAttributes attributes) {
    TComposition.Coverages coverages = {};
    var record = worldGeometryRecord();
    if ((record.flags & RETAINED_GEOMETRY_HAS_VOLUME) != 0u) return;
    float coverage = clamp(coverages.evaluateCoverage(record.coverageImplementation,
            worldCoverageInput(attributes, record)), 0.0, 1.0);
    bool stochastic = (record.flags & RETAINED_GEOMETRY_STOCHASTIC) != 0u
            && !deterministic;
    if (stochastic ? worldCoverageRandom(seed, worldInstanceCustomIndex(),
            GeometryIndex(), PrimitiveIndex()) >= coverage : coverage < record.alphaCutoff) {
        IgnoreHit();
    }
}

public void runRadianceAnyHit<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    rejectCoverageHole<TComposition>(payload.seed,
            (payload.pathFlags & (WORLD_PATH_GUIDE | WORLD_PATH_PRIMARY)) != 0u, attributes);
}

[shader("anyhit")]
void main<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    runRadianceAnyHit<TComposition>(payload, attributes);
}
