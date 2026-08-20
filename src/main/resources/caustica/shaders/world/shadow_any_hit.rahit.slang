// Shadow-ray any-hit: coverage, then transmittance. Shadow traversal skips closest-hit entirely, so every
// material that should pass or tint light does that work here and then ignores the hit so traversal
// continues. The payload's albedo view is the accumulated transmittance and hitT the nearest crossing.
//
// Coverage is resolved deterministically on this ray type: a shadow ray is a visibility answer, not a
// sample that averages out, so a stochastic-coverage material takes its cutoff here.
import world_common;
import bindings;
import any_hit_common;
import caustica_api;
import caustica_pass_resources;

[shader("anyhit")]
void main<C : IComposition>(inout Payload payload, in BuiltInTriangleIntersectionAttributes attr) {
    HitBinding hit = decodeHit(barycentrics(attr.barycentrics), PrimitiveIndex());
    uint flags = bindingFlags(hit.binding);
    C.Coverages coverages;
    float4 resolved = coverages.evaluateCoverage(
            bindingSurfaceImpl(hit.binding), hit.coverageInput);

    if (bindingCoverage(hit.binding) != COVERAGE_OPAQUE) {
        if (resolved.a < bindingCutoff(hit.binding)) {
            IgnoreHit();
        }
    }

    if ((flags & BINDING_TRANSMISSIVE) != 0u) {
        packAlbedo(payload, unpackAlbedo(payload) * resolved.rgb);
        // Any-hit order is arbitrary. Track only a boundary produced by the current medium's
        // implementation, so another transmissive volume cannot steal its crossing.
        bool trackBoundary = (payload.flags & PAYLOAD_TRACK_MEDIUM_BOUNDARY) != 0u;
        uint mediumImplementation = payload.flags >> PAYLOAD_SURFACE_IMPL_SHIFT;
        if (trackBoundary && bindingSurfaceImpl(hit.binding) == mediumImplementation) {
            payload.hitT = payload.hitT < 0.0 ? RayTCurrent() : min(payload.hitT, RayTCurrent());
        }
        IgnoreHit();
    }
}
