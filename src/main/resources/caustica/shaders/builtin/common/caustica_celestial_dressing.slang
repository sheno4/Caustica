module caustica_celestial_dressing;

// Small helpers for what a sky with a visible sun, moon, and starfield draws on top of its atmosphere
// in-scatter. Shared by the Overworld sky slot and any other dimension sky, so a second sky can reuse
// the star machinery with its own cell density and colour ramp instead of copy-pasting it.
//
// Deliberately independent of caustica_sky_atmosphere and its SkyState: every value here is a plain
// scalar or vector the caller derives however its own atmosphere model works, not a dependency on the
// Hillaire LUT machinery.

/**
 * Drawn disc radiance derived from illuminance and the solid angle a body is actually DRAWN at
 * (L = E / Ω), deliberately not from its true surface luminance — see the call site in
 * caustica_lut_sky.slang for why (vanilla's oversized sun quad and the NEE double-count it would cause
 * at the physical value).
 */
public float3 discRadiance(float illuminance, float halfAngle) {
    float side = 2.0 * tan(halfAngle); // the square spans this much on a side, gnomonically
    return float3(illuminance / max(side * side, 1.0e-8));
}

// Dave Hoskins' hash13 (https://www.shadertoy.com/view/4djSRW): well-distributed and pattern-free.
public float hash13(float3 p3) {
    p3 = frac(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return frac((p3.x + p3.y) * p3.z);
}

/** Rotate v about a unit axis by angle (Rodrigues). Used to wheel the starfield about a celestial pole. */
public float3 rotateAxis(float3 v, float3 axis, float ang) {
    float c = cos(ang), s = sin(ang);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

// Procedural starfield, a faithful port of vanilla's buildStars(): discrete stars at random sphere
// directions, each a small SQUARE billboard of random size and rotation. A cube-map grid is the spatial
// accelerator — at most one star per cell, at a hashed position inset from the cell edges (so its square
// never crosses into a neighbour), drawn as a hash-rotated square of hashed size.
static const float STAR_HALF = 0.045; // star half-size as a fraction of a cell (≈ vanilla 0.11° at centre)

/**
 * @param dir World-space look direction.
 * @param axis Unit axis the field wheels about.
 * @param wheelAngle Rotation about {@code axis}; the LOOKUP direction is rotated by {@code -wheelAngle} so
 *        the field wheels the same way as whatever {@code axis}/{@code wheelAngle} otherwise describes
 *        (e.g. the sun's own angle).
 * @param luminance Zero disables the field entirely (no per-star cost paid).
 * @param extinction Per-channel dimming applied to every star, e.g. atmospheric transmittance toward
 *        {@code dir} — stars set at the horizon on the same curve the sky does.
 * @param cellDensity Cube-face grid resolution; higher packs more (smaller, denser) cells.
 * @param threshold Cell occupancy cutoff in [0,1) — higher means fewer stars.
 * @param coolColor / @param warmColor Colour-temperature ramp a per-star hash picks a point on.
 */
public float3 stars(float3 dir, float3 axis, float wheelAngle, float luminance, float3 extinction,
                    float cellDensity, float threshold, float3 coolColor, float3 warmColor) {
    if (luminance <= 0.0 || dir.y <= 0.0) {
        return float3(0.0);
    }
    float3 sdir = rotateAxis(dir, axis, -wheelAngle);
    // Cube-map face + 2D gnomonic uv → uniform square cells, no pole/equator distortion.
    float3 a = abs(sdir);
    float2 uv;
    float face;
    if (a.x >= a.y && a.x >= a.z)      { uv = sdir.yz / a.x; face = sdir.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y >= a.z)               { uv = sdir.xz / a.y; face = sdir.y > 0.0 ? 2.0 : 3.0; }
    else                               { uv = sdir.xy / a.z; face = sdir.z > 0.0 ? 4.0 : 5.0; }
    float2 g = uv * cellDensity;
    float3 id = float3(floor(g), face);
    if (hash13(id) < threshold) {
        return float3(0.0); // no star in this cell
    }
    float sz = STAR_HALF * (0.6 + 0.8 * hash13(id + 53.0));
    float inset = sz * 1.5; // keeps the rotated square clear of the cell edge
    float2 center = inset + (1.0 - 2.0 * inset) * float2(hash13(id + 11.0), hash13(id + 23.0));
    float2 q = frac(g) - center;
    float ang = hash13(id + 91.0) * 6.2831853;            // random per-star rotation (vanilla zRot)
    float cs = cos(ang), sn = sin(ang);
    q = float2(cs * q.x - sn * q.y, sn * q.x + cs * q.y); // GLSL mat2(cs,-sn,sn,cs) * q, written out
    float box = max(abs(q.x), abs(q.y));                  // Chebyshev distance → square
    float star = 1.0 - smoothstep(sz * 0.6, sz, box);     // soft-edged square (helps DLSS stability)
    if (star <= 0.0) {
        return float3(0.0);
    }
    float bright = 0.55 + 0.45 * hash13(id + 71.0);       // mild per-star brightness variation
    float3 starColor = lerp(coolColor, warmColor, hash13(id + 137.0));
    return star * bright * starColor * luminance * extinction;
}
