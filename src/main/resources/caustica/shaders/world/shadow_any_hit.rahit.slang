// Shadow-ray any-hit: coverage, then transmittance. Shadow traversal skips closest-hit entirely, so every
// material that should pass or tint light does that work here and then ignores the hit so traversal
// continues. The payload's albedo view is the accumulated transmittance and hitT the nearest crossing.
//
// Coverage is resolved deterministically on this ray type: a shadow ray is a visibility answer, not a
// sample that averages out, so a stochastic-coverage material takes its cutoff here.
import world_common;
import math;
import bindings;
import any_hit_common;

[shader("anyhit")]
void main(inout Payload payload, in BuiltInTriangleIntersectionAttributes attr) {
    HitBinding hit = decodeHit(barycentrics(attr.barycentrics), PrimitiveIndex());
    uint flags = bindingFlags(hit.binding);

    // Uniform versus per-texel transmittance needs no flag. A non-opaque binding sampled its texel to
    // answer coverage, so that texel is also the colour light picks up crossing the surface — opaque
    // where the colorant is, clear where the sprite is see-through. An opaque binding sampled nothing and
    // takes the compile-time reduction instead, so solid glass costs no texture fetch on a shadow ray.
    float3 geometryTint = hitGeometryTint(hit);
    float3 transmittance = bindingShadowTint(hit.binding) * geometryTint;
    if (bindingCoverage(hit.binding) != COVERAGE_OPAQUE) {
        float4 texel = sampleCoverageTexel(hit);
        float alpha = hitCoverageAlpha(hit, texel.a);
        if (alpha < bindingCutoff(hit.binding)) {
            IgnoreHit();
        }
        float3 baseColor = bt709ToAcesCg(hitBaseColorLinear(hit, texel.rgb));
        transmittance = lerp(float3(1.0), baseColor * geometryTint, alpha);
    }

    if ((flags & BINDING_TRANSMISSIVE) != 0u) {
        // Geometry colour is already folded into transmittance in the same colour/alpha order as
        // closest-hit, so a transmissive shadow sees the same interpolated filter as a radiance ray.
        packAlbedo(payload, unpackAlbedo(payload) * transmittance);
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
