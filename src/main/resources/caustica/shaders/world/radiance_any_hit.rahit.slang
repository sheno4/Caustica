// Radiance-ray any-hit: the coverage test only. Transmittance is an interface behaviour resolved in
// closest-hit on this ray type, so nothing here touches the payload — accepted hits fall through.
//
// Guide probes trace the radiance records with a cheaper miss, but they must not resolve coverage
// stochastically: a guide answers "which surface does this reflection/refraction point at", and a
// coin-flip on that is a flickering control signal rather than a sample that averages out. Taking the
// deterministic cutoff instead lets a translucent covered surface reach closest-hit as a crossing, which is
// exactly what the guide loop already knows how to walk through.
import world_common;
import bindings;
import any_hit_common;

[shader("anyhit")]
void main(inout Payload payload, in BuiltInTriangleIntersectionAttributes attr) {
    uint pid = PrimitiveIndex();
    HitBinding hit = decodeHit(barycentrics(attr.barycentrics), pid);
    uint coverage = bindingCoverage(hit.binding);
    if (coverage == COVERAGE_OPAQUE) {
        return;
    }
    float alpha = hitCoverageAlpha(hit, sampleCoverageTexel(hit).a);
    if (coverage == COVERAGE_STOCHASTIC && (payload.flags & PAYLOAD_GUIDE_RAY) == 0u) {
        if (stochasticAlphaThreshold(pid) >= alpha) {
            IgnoreHit();
        }
    } else if (alpha < bindingCutoff(hit.binding)) {
        IgnoreHit();
    }
}
