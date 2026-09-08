package dev.comfyfluffy.caustica.minecraft.client.terrain;

import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftEmissionFootprint;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialEmission;
import dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainEmitter;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.support.ColorSpaces;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.List;

import static dev.comfyfluffy.caustica.minecraft.rendering.terrain.MinecraftTerrainMesh.PRIMITIVE_FLOATS;


/**
 * Collects section-local rectangle emitters on the meshing worker using immutable material values.
 * Each source quad occupies four vertices and two adjacent triangles. Its light bounds the sampled
 * emissive footprint and uses the rectangle's mean ACEScg radiance, including dark samples.
 */
final class RtLightCollector {
    private RtLightCollector() {
    }

    /** Block-light levels below this are non-emissive (smallest real level is 1/15). */
    private static final float EMISSION_EPS = 0.5f / 255f;

    /** Footprint weights below one encoded byte step don't count as emissive coverage. */
    private static final float WEIGHT_EPS = 1.0f / 255f;

    /** Degenerate rectangles carry no emitted power. */
    private static final float AREA_EPS = 1.0e-9f;

    static void collectClass(List<MinecraftTerrainEmitter> out, FloatArrayList verts, FloatArrayList prim,
                              FloatArrayList cornerUv, TextureAtlasSprite[] sprites,
                              MinecraftMaterialEmission[] materialEmissions,
                              float minFillRatio) {
        int quads = prim.size() / (2 * PRIMITIVE_FLOATS);
        float[] v = verts.elements();
        float[] p = prim.elements();
        float[] uv = cornerUv.elements();
        for (int k = 0; k < quads; k++) {
            int pb = k * 2 * PRIMITIVE_FLOATS;
            MinecraftMaterialEmission material = materialEmissions[2 * k];
            if (material.luminanceCdM2() == 0.0f) {
                continue;
            }
            float leLuminanceEps = 0.001f * material.luminanceCdM2();

            float stateEmission = p[pb + 3];
            float factor = material.textureMapped() ? stateEmission : 1.0f;
            if (factor <= EMISSION_EPS) {
                continue;
            }
            MinecraftEmissionFootprint footprint = material.footprint();
            int scan = footprint.resolution();

            // The first, second and fourth vertices define a parallelogram approximation of the quad.
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

            // Scan the quad's parameter square. Quads without a sprite use those coordinates directly;
            // sprite-backed quads localize their interpolated atlas coordinates before sampling.
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
                    int cx = footprint.sampleIndex(lu);
                    int cy = footprint.sampleIndex(lv);
                    float w = footprint.weight(cx, cy);
                    float r = footprint.r(cx, cy);
                    float g = footprint.g(cx, cy);
                    float bl = footprint.b(cx, cy);
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
            // sum/rectSamples preserves the quad's total emissive power at rectArea. luminanceCdM2()
            // is the final Minecraft material luminance after its matching resource rule.
            // Footprint averages are linear BT.709; terrain extraction stores triangle tint as ACEScg.
            // Convert the footprint before combining them in the transport basis.
            float[] footprintAcesCg = ColorSpaces.linearBt709ToAcesCg(sumR, sumG, sumB);
            float scale = factor * material.luminanceCdM2() / rectSamples;
            float leR = footprintAcesCg[0] * scale * p[pb + 4];
            float leG = footprintAcesCg[1] * scale * p[pb + 5];
            float leB = footprintAcesCg[2] * scale * p[pb + 6];
            float lum = 0.27222872f * leR + 0.67408177f * leG + 0.05368952f * leB;
            if (lum < leLuminanceEps || fill < minFillRatio) {
                continue;
            }

            float aC = 0.5f * (aLo + aHi);
            float bC = 0.5f * (bLo + bHi);
            append(out,
                    c0x + aC * e01x + bC * e03x, c0y + aC * e01y + bC * e03y, c0z + aC * e01z + bC * e03z,
                    p[pb], p[pb + 1], p[pb + 2],
                    2 * k,
                    0.5f * (aHi - aLo) * e01x, 0.5f * (aHi - aLo) * e01y, 0.5f * (aHi - aLo) * e01z,
                    0.5f * (bHi - bLo) * e03x, 0.5f * (bHi - bLo) * e03y, 0.5f * (bHi - bLo) * e03z,
                    leR, leG, leB);
        }
    }

    static void append(List<MinecraftTerrainEmitter> out,
                       float px, float py, float pz, float nx, float ny, float nz, int firstPrimitive,
                       double ux, double uy, double uz, double vx, double vy, double vz,
                       float red, float green, float blue) {
        // Match the shaded face's orientation, including mirrored and skewed fluid quads.
        double facing = (uy * vz - uz * vy) * nx
                + (uz * vx - ux * vz) * ny + (ux * vy - uy * vx) * nz;
        if (facing < 0.0) {
            vx = -vx;
            vy = -vy;
            vz = -vz;
        }
        var descriptor = new LightDescriptor.Parallelogram(px, py, pz,
                ux, uy, uz, vx, vy, vz, red, green, blue);
        out.add(new MinecraftTerrainEmitter(descriptor, firstPrimitive, 2));
    }
}
