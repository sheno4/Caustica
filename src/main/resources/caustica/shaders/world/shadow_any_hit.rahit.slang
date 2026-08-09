// Shadow-ray any-hit: alpha masking plus transmittance accumulation. Shadow traversal skips closest-hit
// entirely, so every material that should pass or tint sunlight does that work here and then ignores the
// hit so traversal continues. The payload's albedo view is the accumulated transmittance and hitT is the
// nearest water crossing.
import world_common;
import math;
import bindings;
import any_hit_common;

[shader("anyhit")]
void main(inout Payload payload, in BuiltInTriangleIntersectionAttributes attr) {
    float3 bary = barycentrics(attr.barycentrics);
    uint pid = PrimitiveIndex();

    uint instanceKind = InstanceID() & (ENTITY_BIT | PARTICLE_BIT);
    if (instanceKind != 0u) {
        EntityHit hit = decodeEntityHit(bary, pid);
        if (entityAlphaFails(hit, pid, instanceKind == ENTITY_BIT)) {
            IgnoreHit();
        }
        if (instanceKind != ENTITY_BIT) {
            return;
        }
        // Entity dielectrics and water transmit instead of becoming opaque shadow blockers, using the
        // same material models the terrain buckets below do.
        float transmission = clamp(hit.material.params.w, 0.0, 1.0);
        if (hit.material.model == MATERIAL_DIELECTRIC) {
            float3 tint709 = lerp(float3(1.0),
                    srgbToLinear(hit.texel.rgb) * srgbToLinear(hit.prim.tint.rgb), hit.texel.a);
            packAlbedo(payload, unpackAlbedo(payload) * bt709ToAcesCg(tint709) * transmission);
            IgnoreHit();
        }
        if (hit.material.model == MATERIAL_WATER) {
            float3 tint = bt709ToAcesCg(
                    srgbToLinear(hit.texel.rgb) * srgbToLinear(hit.prim.tint.rgb));
            packAlbedo(payload, unpackAlbedo(payload)
                    * lerp(float3(1.0, 1.0, 1.0), tint, WATER_SHADOW_TINT) * transmission);
            payload.hitT = payload.hitT < 0.0 ? RayTCurrent() : min(payload.hitT, RayTCurrent());
            IgnoreHit();
        }
        return;
    }

    Section sec = ConstPtr<Section>(pc.tableAddr)[InstanceID()];
    uint bucket = GeometryIndex();
    uint tri = pid + sec.triBase[bucket];

    // Tint sunlight by the biome water color, then keep walking so submerged terrain is lit by colored
    // transmission. Record the NEAREST crossing (any-hit order is arbitrary): for an underwater shading
    // point that is the exit point of its sun shadow ray, where world.rgen evaluates the wave-refraction
    // caustic. visibility() seeds the -1 sentinel.
    if (bucket == BUCKET_WATER) {
        TerrainPrim pr = ConstPtr<TerrainPrim>(sec.primAddr)[tri];
        float3 tint = bt709ToAcesCg(srgbToLinear(pr.tint.rgb));
        packAlbedo(payload, unpackAlbedo(payload) * lerp(float3(1.0), tint, WATER_SHADOW_TINT));
        payload.hitT = payload.hitT < 0.0 ? RayTCurrent() : min(payload.hitT, RayTCurrent());
        IgnoreHit();
    }

    // Translucent panes tint and pass through. This doesn't touch the atlas at all: a shadow ray only
    // needs the pane's overall tint/opacity, so MaterialHeader carries a precomputed whole-sprite average
    // {r,g,b,a}; per-primitive biome tint is applied here. The average is compiled once per resource epoch
    // rather than copied per triangle.
    //
    // Modeled as Beer-Lambert absorption (matching the water medium in world.rgen): a per-channel
    // extinction derived from how dark the average is, scaled by the average alpha (how much of the
    // sprite is glass-colorant vs. see-through frame), so saturated panes darken transmitted light
    // non-linearly. Multiplying into the albedo view compounds correctly across stacked panes.
    if (bucket == BUCKET_TRANSLUCENT) {
        TerrainPrim pr = ConstPtr<TerrainPrim>(sec.primAddr)[tri];
        MaterialHeader materialHeader = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId];
        float3 avgColor = max(bt709ToAcesCg(
                materialHeader.average.rgb * srgbToLinear(pr.tint.rgb)), float3(1.0e-3));
        float3 colorExtinction = max(-log(avgColor), float3(0.0, 0.0, 0.0));
        // The neutral floor is a flat per-hit dimming, NOT scaled by alpha: vanilla clear glass has a low
        // natural alpha, so folding it into the alpha-scaled term crushed it to near-zero for exactly the
        // white/clear-glass case it's meant to cover.
        packAlbedo(payload, unpackAlbedo(payload)
                * exp(-colorExtinction * materialHeader.average.a - TRANSLUCENT_NEUTRAL_EXTINCTION));
        IgnoreHit();
    }

    if (terrainCutoutFails(sec, bary, tri)) {
        IgnoreHit();
    }
}
