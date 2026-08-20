package dev.comfyfluffy.caustica.rt.material;

import java.util.ArrayList;
import java.util.List;

/** CPU-side canonical material texels and semantic mip reduction. All channels are physical values. */
final class RtMaterialTextureData {
    static final int CHANNELS = 4;

    // sRGB byte -> linear float. Every decode input is 8-bit, so the exact transfer function
    // collapses to one 256-entry table instead of a Math.pow per texel on the reload path.
    private static final float[] SRGB_TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            float value = i / 255.0f;
            SRGB_TO_LINEAR[i] = value <= 0.04045f ? value / 12.92f
                    : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
        }
    }

    static float srgbToLinear(int value8) {
        return SRGB_TO_LINEAR[value8 & 0xFF];
    }

    private RtMaterialTextureData() {
    }

    record Level(int width, int height, float[] surface0, float[] normal, float[] surface1,
                 float[] emissionColor) {
        Level {
            int values = Math.multiplyExact(Math.multiplyExact(width, height), CHANNELS);
            if (width <= 0 || height <= 0 || surface0.length != values
                    || normal.length != values || surface1.length != values
                    || emissionColor.length != values) {
                throw new IllegalArgumentException("Invalid canonical material level");
            }
        }
    }

    static List<Level> mipChain(Level base, int maxLevel) {
        List<Level> levels = new ArrayList<>(maxLevel + 1);
        levels.add(base);
        while (levels.size() <= maxLevel) {
            levels.add(reduce(levels.get(levels.size() - 1)));
        }
        return levels;
    }

    /**
     * Reduce already-decoded physical channels. Emission is energy-averaged, normals are averaged then
     * renormalized, and lost normal length raises roughness so distant normal detail does not become a
     * falsely smooth surface. Roughness is stored perceptual (OpenPBR r) but reduced in GGX alpha, which
     * is where normal-map variance actually adds — see the Toksvig term below.
     */
    static Level reduce(Level src) {
        int width = Math.max(1, (src.width + 1) / 2);
        int height = Math.max(1, (src.height + 1) / 2);
        float[] surface0 = new float[width * height * CHANNELS];
        float[] normal = new float[surface0.length];
        float[] surface1 = new float[surface0.length];
        float[] emissionColor = new float[surface0.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float alphaSum = 0.0f;
                float metal = 0.0f, emission = 0.0f, sss = 0.0f;
                float nx = 0.0f, ny = 0.0f, nz = 0.0f, heightValue = 0.0f;
                float specR = 0.0f, specG = 0.0f, specB = 0.0f, ior = 0.0f;
                float emissionR = 0.0f, emissionG = 0.0f, emissionB = 0.0f;
                float emissionWeight = 0.0f;
                int samples = 0;
                for (int oy = 0; oy < 2; oy++) {
                    int sy = y * 2 + oy;
                    if (sy >= src.height) continue;
                    for (int ox = 0; ox < 2; ox++) {
                        int sx = x * 2 + ox;
                        if (sx >= src.width) continue;
                        int si = (sy * src.width + sx) * CHANNELS;
                        float roughness = src.surface0[si];
                        alphaSum += roughness * roughness;
                        metal += src.surface0[si + 1];
                        emission += src.surface0[si + 2];
                        float weight = src.surface0[si + 2];
                        emissionR += src.emissionColor[si] * weight;
                        emissionG += src.emissionColor[si + 1] * weight;
                        emissionB += src.emissionColor[si + 2] * weight;
                        emissionWeight += weight;
                        sss += src.surface0[si + 3];

                        float tx = src.normal[si] * 2.0f - 1.0f;
                        float ty = src.normal[si + 1] * 2.0f - 1.0f;
                        float tz = (float) Math.sqrt(Math.max(0.0f, 1.0f - tx * tx - ty * ty));
                        nx += tx;
                        ny += ty;
                        nz += tz;
                        heightValue += src.normal[si + 3];

                        specR += src.surface1[si];
                        specG += src.surface1[si + 1];
                        specB += src.surface1[si + 2];
                        ior += src.surface1[si + 3];
                        samples++;
                    }
                }
                float inv = 1.0f / samples;
                nx *= inv;
                ny *= inv;
                nz *= inv;
                float normalLength = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (normalLength > 1.0e-6f) {
                    nx /= normalLength;
                    ny /= normalLength;
                } else {
                    nx = ny = 0.0f;
                }
                int di = (y * width + x) * CHANNELS;
                // Toksvig-style variance term. This is intentionally conservative and monotonic.
                // Averaging and widening both happen in GGX alpha, which is where normal-map variance
                // adds: alpha behaves like a variance, so both are plain sums. The result goes back to
                // perceptual roughness because that is what the page stores.
                float alpha = clamp01(alphaSum * inv + Math.max(0.0f, 1.0f - normalLength));
                surface0[di] = (float) Math.sqrt(alpha);
                surface0[di + 1] = clamp01(metal * inv);
                surface0[di + 2] = clamp01(emission * inv);
                surface0[di + 3] = clamp01(sss * inv);
                normal[di] = clamp01(nx * 0.5f + 0.5f);
                normal[di + 1] = clamp01(ny * 0.5f + 0.5f);
                normal[di + 3] = clamp01(heightValue * inv);
                surface1[di] = clamp01(specR * inv);
                surface1[di + 1] = clamp01(specG * inv);
                surface1[di + 2] = clamp01(specB * inv);
                surface1[di + 3] = clamp01(ior * inv);
                float emissionInv = emissionWeight > 1.0e-6f ? 1.0f / emissionWeight : 0.0f;
                emissionColor[di] = clamp01(emissionR * emissionInv);
                emissionColor[di + 1] = clamp01(emissionG * emissionInv);
                emissionColor[di + 2] = clamp01(emissionB * emissionInv);
                emissionColor[di + 3] = 1.0f;
            }
        }
        return new Level(width, height, surface0, normal, surface1, emissionColor);
    }

    static int unorm8(float value) {
        return Math.round(clamp01(value) * 255.0f);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
