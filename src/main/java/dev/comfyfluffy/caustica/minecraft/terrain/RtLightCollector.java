package dev.comfyfluffy.caustica.minecraft.terrain;

import dev.comfyfluffy.caustica.api.ColorSpaces;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.api.provider.MaterialAnalysis;
import dev.comfyfluffy.caustica.api.provider.EmissionFootprint;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.List;

/**
 * RIS emitter-NEE light collection. Enumerates a section's emissive terrain quads into a
 * samplable light list, in <b>section-local</b> coordinates (flattened into rebased world space at
 * publish, see {@code RtTerrain.applyBuildChanges}). Runs on the meshing worker over the transient
 * per-class arrays, before packing — pure CPU + material-snapshot reads only.
 *
 * <p><b>One rectangle light per emissive quad.</b> {@code emit()}/{@code emitQuad()} always write a quad
 * as two lockstep triangles (0,1,2)(0,2,3) over 4 consecutive verts with prim/cornerUv records in step,
 * so quad {@code k} is triangles {@code 2k, 2k+1} and its corners are verts {@code 4k..4k+3}.
 * The light is the emissive footprint's <b>bounding rectangle</b> (half-axes in the
 * record): it doesn't overshoot the emitter shape, and its (s,t) parameterization <i>is</i> the affine
 * sprite-local UV map used for exact radiance lookup.
 *
 * <p><b>Radiance matches the closest-hit.</b> Per-texel shaded emission is {@code albedo * mask *
 * emissionLuminance}, where the mask source (LabPBR {@code _s} blue channel / heuristic mask x block
 * light / uniform block light) is exactly what {@code world.rchit.evaluateMaterial} resolves, and
 * {@code emissionLuminance} is the material snapshot's luminance — the material-compile-time
 * baseline replaced by any resource-pack override, the single knob shared with the shader. The
 * per-material {@link EmissionFootprint} stores the same premultiplied linear emission color and mask
 * coverage. The light's radiance is the mean over its bounding rectangle (dark texels included — a uniform-rectangle
 * approximation), so total power equals the quad's true emissive integral: the rectangle contains every
 * emissive sample, hence {@code Le_rect * rectArea == quadArea * mean(albedo*mask)}.
 *
 * <p><b>Membership.</b> A scene-linked quad marks both neutral triangle surfaces, so the renderer can
 * gate its direct-hit emission term. Emitters too weak or too sparse (fill-ratio
 * gate) stay excluded and are always-gathered on path hits — bit-identical to the no-NEE path.
 */
final class RtLightCollector {
    private RtLightCollector() {
    }

    /** Floats per packed light record — see {@link #append} for the 5-vec4 layout. */
    static final int FLOATS_PER_LIGHT = 20;

    /** Block-light levels below this are non-emissive (smallest real level is 1/15). */
    private static final float EMISSION_EPS = 0.5f / 255f;

    /** Footprint weights below this don't count as emissive coverage (mirrors the summary's 1/255). */
    private static final float WEIGHT_EPS = 1.0f / 255f;

    /** Degenerate (zero-area) rectangles carry no power and would NaN the estimator — skip them. */
    private static final float AREA_EPS = 1.0e-9f;

    /**
     * An emitter whose rectangle-mean radiance luminance is below this is too weak to bother sampling:
     * keep it out of the buffer (always-gathered on hits instead).
     *
     * <p>Expressed as a fraction of the emissive baseline rather than as an absolute radiance, because
     * "too weak to sample" is a statement about this emitter relative to a full-strength one, not about
     * cd/m². Expressing it relative to the configured baseline keeps material brightness and sampling
     * eligibility independent.
     */
    private static final int PRIM_FLOATS = 12; // CPU material/light lanes per triangle

    /**
     * Collect one geometry class's emissive quads into {@code out} (packed light records,
     * section-local) and mark matching neutral triangle surfaces. Only the opaque
     * and masked classes can emit: glass is shaded with zero emission and water never emits (lava lives
     * in the opaque class).
     */
    static void collectClass(FloatArrayList out, FloatArrayList verts, FloatArrayList prim,
                              List<SceneMesh.TriangleSurface> surfaces,
                              FloatArrayList cornerUv, TextureAtlasSprite[] sprites,
                              MaterialAnalysis[] materialAnalyses, float minFillRatio) {
        int quads = prim.size() / (2 * PRIM_FLOATS);
        float[] v = verts.elements();
        float[] p = prim.elements();
        float[] uv = cornerUv.elements();
        for (int k = 0; k < quads; k++) {
            int pb = k * 2 * PRIM_FLOATS;
            MaterialAnalysis material = materialAnalyses[2 * k];
            float leLuminanceEps = 0.001f * material.emissionLuminanceCdM2();
            MaterialAnalysis.EmissionSource source = material.emissionSource();
            if (source == MaterialAnalysis.EmissionSource.NONE) {
                continue;
            }

            float stateEmission = p[pb + 3];
            float factor = switch (source) {
                case AUTHORED_MASK -> 1.0f;
                case DERIVED_MASK, GEOMETRY_UNIFORM -> stateEmission;
                case NONE -> 0.0f;
            };
            if (factor <= EMISSION_EPS) {
                continue;
            }
            EmissionFootprint footprint = material.emissionFootprint();
            if (footprint == null && source != MaterialAnalysis.EmissionSource.GEOMETRY_UNIFORM) {
                continue; // masked source with no emissive texels
            }
            int scan = footprint != null
                    ? footprint.resolution() : material.emissionFootprintResolution();

            // Quad corners: 4 consecutive verts. Parallelogram frame (exact for block faces, the same
            // approximation the barycentric UV map below already makes for irregular model quads).
            int vb = k * 12;
            float c0x = v[vb], c0y = v[vb + 1], c0z = v[vb + 2];
            float e01x = v[vb + 3] - c0x, e01y = v[vb + 4] - c0y, e01z = v[vb + 5] - c0z;
            float e03x = v[vb + 9] - c0x, e03y = v[vb + 10] - c0y, e03z = v[vb + 11] - c0z;
            float crx = e01y * e03z - e01z * e03y;
            float cry = e01z * e03x - e01x * e03z;
            float crz = e01x * e03y - e01y * e03x;
            float quadArea = (float) Math.sqrt(crx * crx + cry * cry + crz * crz);
            if (quadArea <= AREA_EPS) {
                continue;
            }

            // Corner atlas UVs: triangle A carries corners 0,1,2 (6 floats at 12k), triangle B's third
            // vertex is corner 3 (floats 12k+6+4, +5).
            int ub = k * 12;
            float u0 = uv[ub], v0 = uv[ub + 1];
            float u1 = uv[ub + 2], v1 = uv[ub + 3];
            float u2 = uv[ub + 4], v2 = uv[ub + 5];
            float u3 = uv[ub + 10], v3 = uv[ub + 11];
            TextureAtlasSprite sprite = sprites[2 * k];
            float su0 = 0.0f, sv0 = 0.0f, invDu = 1.0f, invDv = 1.0f;
            boolean localUv = sprite != null;
            if (localUv) {
                su0 = sprite.getU0();
                sv0 = sprite.getV0();
                float du = sprite.getU1() - su0;
                float dv = sprite.getV1() - sv0;
                invDu = Math.abs(du) > 1.0e-12f ? 1.0f / du : 0.0f;
                invDv = Math.abs(dv) > 1.0e-12f ? 1.0f / dv : 0.0f;
            }

            // Footprint scan over the quad's (a,b) parameter square. Fluid quads (null sprite) can't be
            // localized into the sprite rect; they sample the footprint by (a,b) directly — positionally
            // approximate but color-exact, and fluid emitters (lava) are near-uniform anyway.
            float sumR = 0.0f, sumG = 0.0f, sumB = 0.0f;
            int emissive = 0;
            int aMin = scan, aMax = -1, bMin = scan, bMax = -1;
            for (int sb = 0; sb < scan; sb++) {
                float b = (sb + 0.5f) / scan;
                for (int sa = 0; sa < scan; sa++) {
                    float a = (sa + 0.5f) / scan;
                    float lu;
                    float lv;
                    if (localUv) {
                        float au = (1 - a) * (1 - b) * u0 + a * (1 - b) * u1 + a * b * u2 + (1 - a) * b * u3;
                        float av = (1 - a) * (1 - b) * v0 + a * (1 - b) * v1 + a * b * v2 + (1 - a) * b * v3;
                        lu = (au - su0) * invDu;
                        lv = (av - sv0) * invDv;
                    } else {
                        lu = a;
                        lv = b;
                    }
                    float w;
                    float r;
                    float g;
                    float bl;
                    if (footprint != null) {
                        int cx = footprint.sampleIndex(lu);
                        int cy = footprint.sampleIndex(lv);
                        w = footprint.weight(cx, cy);
                        r = footprint.r(cx, cy);
                        g = footprint.g(cx, cy);
                        bl = footprint.b(cx, cy);
                    } else {
                        w = 1.0f; // uniform source without a footprint: flat white (albedo unknown)
                        r = 1.0f;
                        g = 1.0f;
                        bl = 1.0f;
                    }
                    sumR += r;
                    sumG += g;
                    sumB += bl;
                    if (w > WEIGHT_EPS) {
                        emissive++;
                        if (sa < aMin) aMin = sa;
                        if (sa > aMax) aMax = sa;
                        if (sb < bMin) bMin = sb;
                        if (sb > bMax) bMax = sb;
                    }
                }
            }
            if (emissive == 0) {
                continue;
            }

            // Emissive-footprint bounding rectangle in (a,b), expanded to the scan cells' outer edges.
            float aLo = aMin / (float) scan, aHi = (aMax + 1) / (float) scan;
            float bLo = bMin / (float) scan, bHi = (bMax + 1) / (float) scan;
            int rectSamples = (aMax - aMin + 1) * (bMax - bMin + 1);
            float fill = emissive / (float) rectSamples;
            float rectArea = quadArea * (aHi - aLo) * (bHi - bLo);
            if (rectArea <= AREA_EPS) {
                continue;
            }

            // Rectangle-mean radiance: every emissive sample lies inside the rectangle, so
            // sum/rectSamples preserves the quad's total emissive power at rectArea. emissionLuminance()
            // is the material's final HDR luminance (catalog baseline or absolute JSON override,
            // published in the material epoch) — the single knob shared with world.rchit's direct-hit shading.
            // Footprint averages are linear BT.709; triangle tint already crossed the SceneMesh boundary
            // as ACEScg. Convert the footprint before combining them in the transport basis.
            float[] footprintAcesCg = ColorSpaces.linearBt709ToAcesCg(sumR, sumG, sumB);
            float scale = factor * material.emissionLuminanceCdM2() / rectSamples;
            float leR = footprintAcesCg[0] * scale * p[pb + 4];
            float leG = footprintAcesCg[1] * scale * p[pb + 5];
            float leB = footprintAcesCg[2] * scale * p[pb + 6];
            float lum = 0.27222872f * leR + 0.67408177f * leG + 0.05368952f * leB;
            if (lum < leLuminanceEps || fill < minFillRatio) {
                continue; // excluded: always-gathered on path hits, no energy lost
            }

            float aC = 0.5f * (aLo + aHi);
            float bC = 0.5f * (bLo + bHi);
            // Sprite-local UV frame of the rectangle: affine map from the light's
            // (s,t) in [-1,1]^2 to sprite-local UV, evaluated from the same bilinear corner map.
            float uvCu;
            float uvCv;
            float uvHuU;
            float uvHuV;
            float uvHvU;
            float uvHvV;
            if (localUv) {
                uvCu = localU(aC, bC, u0, u1, u2, u3, su0, invDu);
                uvCv = localU(aC, bC, v0, v1, v2, v3, sv0, invDv);
                uvHuU = localU(aHi, bC, u0, u1, u2, u3, su0, invDu) - uvCu;
                uvHuV = localU(aHi, bC, v0, v1, v2, v3, sv0, invDv) - uvCv;
                uvHvU = localU(aC, bHi, u0, u1, u2, u3, su0, invDu) - uvCu;
                uvHvV = localU(aC, bHi, v0, v1, v2, v3, sv0, invDv) - uvCv;
            } else {
                uvCu = aC;
                uvCv = bC;
                uvHuU = 0.5f * (aHi - aLo);
                uvHuV = 0.0f;
                uvHvU = 0.0f;
                uvHvV = 0.5f * (bHi - bLo);
            }

            append(out,
                    c0x + aC * e01x + bC * e03x, c0y + aC * e01y + bC * e03y, c0z + aC * e01z + bC * e03z,
                    rectArea,
                    p[pb], p[pb + 1], p[pb + 2],
                    0.5f * (aHi - aLo) * e01x, 0.5f * (aHi - aLo) * e01y, 0.5f * (aHi - aLo) * e01z,
                    packHalf2(uvHuU, uvHuV),
                    0.5f * (bHi - bLo) * e03x, 0.5f * (bHi - bLo) * e03y, 0.5f * (bHi - bLo) * e03z,
                    packHalf2(uvHvU, uvHvV),
                    leR, leG, leB, packHalf2(uvCu, uvCv));

            markEmitterInLightScene(surfaces, 2 * k);
        }
    }

    static void markEmitterInLightScene(List<SceneMesh.TriangleSurface> surfaces, int firstTriangle) {
        surfaces.set(firstTriangle, surfaces.get(firstTriangle).withEmitterInLightScene());
        surfaces.set(firstTriangle + 1, surfaces.get(firstTriangle + 1).withEmitterInLightScene());
    }

    /** Bilinear corner interpolation localized into the sprite rect (shared by U and V lanes). */
    private static float localU(float a, float b, float q0, float q1, float q2, float q3,
                                float origin, float inverse) {
        float atlas = (1 - a) * (1 - b) * q0 + a * (1 - b) * q1 + a * b * q2 + (1 - a) * b * q3;
        return (atlas - origin) * inverse;
    }

    /** Two halves in one float lane (world_common.slang {@code unpackHalf2} order: x low, y high). */
    private static float packHalf2(float x, float y) {
        int bits = (Float.floatToFloat16(y) << 16) | (Float.floatToFloat16(x) & 0xFFFF);
        return Float.intBitsToFloat(bits);
    }

    /**
     * Packed light record, 5 vec4s / 80 B (matches the shader struct):
     * {@code {pos.xyz, rectArea} {normal.xyz, reserved} {halfU.xyz, packHalf2(uvHu)}
     * {halfV.xyz, packHalf2(uvHv)} {Le2020.rgb, packHalf2(uvCenter)}}. Positions/axes section-local
     * here; publish adds the section-origin-minus-rebase offset to pos only.
     */
    private static void append(FloatArrayList out,
                               float px, float py, float pz, float area,
                               float nx, float ny, float nz,
                               float hux, float huy, float huz, float uvHu,
                               float hvx, float hvy, float hvz, float uvHv,
                               float leR, float leG, float leB, float uvC) {
        out.add(px);
        out.add(py);
        out.add(pz);
        out.add(area);
        out.add(nx);
        out.add(ny);
        out.add(nz);
        out.add(0.0f);
        out.add(hux);
        out.add(huy);
        out.add(huz);
        out.add(uvHu);
        out.add(hvx);
        out.add(hvy);
        out.add(hvz);
        out.add(uvHv);
        out.add(leR);
        out.add(leG);
        out.add(leB);
        out.add(uvC);
    }
}
