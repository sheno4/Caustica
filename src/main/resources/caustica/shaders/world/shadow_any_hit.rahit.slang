// Shadow-ray any-hit: coverage, then transmittance. Shadow traversal skips closest-hit entirely, so every
// material that should pass or tint sunlight does that work here and then ignores the hit so traversal
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
    float3 transmittance = bindingShadowTint(hit.binding);
    if (bindingCoverage(hit.binding) != COVERAGE_OPAQUE) {
        float4 texel = sampleCoverageTexel(hit);
        if (texel.a < bindingCutoff(hit.binding)) {
            IgnoreHit();
        }
        transmittance = bt709ToAcesCg(lerp(float3(1.0), srgbToLinear(texel.rgb), texel.a));
    }

    if ((flags & BINDING_TRANSMISSIVE) != 0u) {
        // Per-primitive tint multiplies base colour, and transmission colour is authored relative to it,
        // so source-tinted water and dyed glass both flow through here without a second meaning.
        packAlbedo(payload, unpackAlbedo(payload) * transmittance
                * bt709ToAcesCg(srgbToLinear(hit.tint)));
        // Any-hit order is arbitrary, so keep the nearest: for an underwater shading point that is where
        // its sun shadow ray leaves the medium. visibility() seeds the -1 sentinel.
        if ((flags & BINDING_RECORD_CROSSING) != 0u) {
            payload.hitT = payload.hitT < 0.0 ? RayTCurrent() : min(payload.hitT, RayTCurrent());
        }
        IgnoreHit();
    }
}
