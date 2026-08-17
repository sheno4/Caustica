package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.engine.material.EmissionFootprint;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureAsset;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureImage;
import dev.comfyfluffy.caustica.engine.material.OpenPbrColorBinding;
import dev.comfyfluffy.caustica.engine.material.OpenPbrTextureTexel;

import java.util.List;

/** Decodes source images and computes the statistics consumed by material table compilation. */
final class MaterialTextureAnalyzer {
    record Alpha(float[] texels, float minAlpha, float maxAlpha) { }

    record AlbedoStats(float averageR, float averageG, float averageB, float averageA,
                       RtMaterialDesc.EmissionSummary uniformEmissionSummary,
                       EmissionFootprint uniformEmissionFootprint) {
        static final AlbedoStats NEUTRAL = new AlbedoStats(1, 1, 1, 0,
                RtMaterialDesc.EmissionSummary.NONE, null);
    }

    record Decoded(List<RtMaterialTextureData.Level> levels,
                   RtMaterialDesc.EmissionSummary emissionSummary,
                   EmissionFootprint emissionFootprint, AlbedoStats stats) { }

    private MaterialTextureAnalyzer() { }

    static Decoded decode(MaterialTextureAsset asset, int footprintResolution, int maxLod) throws Exception {
        try (MaterialTextureImage texture = asset.texture().open()) {
            int width = asset.width(), height = asset.height();
            float[] surface0 = new float[width * height * 4];
            float[] normal = new float[surface0.length];
            float[] surface1 = new float[surface0.length];
            float[] emission = asset.emissionMask() ? new float[width * height] : null;
            float[] emissionColor = emission != null ? new float[surface0.length] : null;
            boolean emissionUsesBase = asset.emissionColorBinding() == OpenPbrColorBinding.BASE_COLOR;
            StatsAccumulator stats = new StatsAccumulator(width, height, footprintResolution,
                    asset.emissionColorBinding());
            OpenPbrTextureTexel texel = new OpenPbrTextureTexel();
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                int pixel = sample(texture, x, y, width, height);
                stats.add(x, y, pixel);
                float r = RtMaterialTextureData.srgbToLinear(red(pixel));
                float g = RtMaterialTextureData.srgbToLinear(green(pixel));
                float b = RtMaterialTextureData.srgbToLinear(blue(pixel));
                float a = alpha(pixel) / 255.0f;
                if (emissionColor != null) {
                    emissionColor[i] = emissionUsesBase ? r : 1;
                    emissionColor[i + 1] = emissionUsesBase ? g : 1;
                    emissionColor[i + 2] = emissionUsesBase ? b : 1;
                    emissionColor[i + 3] = a;
                }
                texel.reset();
                texture.readOpenPbr(x, y, texel);
                surface0[i] = texel.specularRoughness;
                surface0[i + 1] = texel.baseMetalness;
                surface0[i + 2] = texel.emissionWeight;
                surface0[i + 3] = texel.subsurfaceWeight;
                normal[i] = texel.tangentNormalX * .5f + .5f;
                normal[i + 1] = texel.tangentNormalY * .5f + .5f;
                normal[i + 3] = texel.normalHeight;
                surface1[i] = texel.metalBaseColorR;
                surface1[i + 1] = texel.metalBaseColorG;
                surface1[i + 2] = texel.metalBaseColorB;
                surface1[i + 3] = MaterialPagePacker.encodeIor(texel.specularIor);
                if (emission != null) emission[y * width + x] = texel.emissionWeight * a;
            }
            RtMaterialDesc.EmissionSummary summary = emission == null
                    ? RtMaterialDesc.EmissionSummary.NONE : summarize(emissionColor, emission);
            EmissionFootprint footprint = emission == null ? null
                    : emissionFootprint(emissionColor, emission, width, height, footprintResolution);
            return new Decoded(RtMaterialTextureData.mipChain(new RtMaterialTextureData.Level(width, height,
                    surface0, normal, surface1), maxLod), summary, footprint, stats.finish());
        }
    }

    static Alpha scanAlpha(MaterialTextureImage texture, int width, int height) {
        if (texture.alphaFrameCount() <= 0) throw new IllegalArgumentException("Material texture has no alpha frames");
        float[] texels = new float[width * height * 4];
        int materialMin = 255, materialMax = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int min = 255, max = 0;
            for (int frame = 0; frame < texture.alphaFrameCount(); frame++) {
                int value = alpha(texture.alphaArgb(frame, x, y));
                min = Math.min(min, value); max = Math.max(max, value);
            }
            int i = (y * width + x) * 4;
            texels[i] = min / 255f; texels[i + 1] = max / 255f;
            materialMin = Math.min(materialMin, min); materialMax = Math.max(materialMax, max);
        }
        return new Alpha(texels, materialMin / 255f, materialMax / 255f);
    }

    static Alpha scanAlpha(MaterialTextureAsset asset) throws Exception {
        try (MaterialTextureImage texture = asset.texture().open()) {
            return scanAlpha(texture, asset.width(), asset.height());
        }
    }

    static boolean hasTemporalVariation(Alpha alpha) {
        for (int i = 0; i < alpha.texels.length; i += 4) if (alpha.texels[i] != alpha.texels[i + 1]) return true;
        return false;
    }

    static AlbedoStats scanAlbedo(MaterialTextureAsset asset, int resolution) throws Exception {
        try (MaterialTextureImage image = asset.texture().open()) {
            StatsAccumulator stats = new StatsAccumulator(asset.width(), asset.height(), resolution,
                    asset.emissionColorBinding());
            for (int y = 0; y < asset.height(); y++) for (int x = 0; x < asset.width(); x++)
                stats.add(x, y, sample(image, x, y, asset.width(), asset.height()));
            return stats.finish();
        }
    }

    private static EmissionFootprint emissionFootprint(float[] color, float[] mask, int width, int height,
                                                        int resolution) {
        EmissionFootprint.Builder result = new EmissionFootprint.Builder(resolution, width, height);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int p = y * width + x, i = p * 4; float w = Math.clamp(mask[p], 0, 1);
            result.add(x, y, color[i] * w, color[i + 1] * w, color[i + 2] * w, w);
        }
        return result.build();
    }

    private static RtMaterialDesc.EmissionSummary summarize(float[] color, float[] mask) {
        double r = 0, g = 0, b = 0, energy = 0; int covered = 0;
        for (int p = 0; p < mask.length; p++) {
            int i = p * 4; float w = Math.clamp(mask[p], 0, 1);
            float er = color[i] * w, eg = color[i + 1] * w, eb = color[i + 2] * w;
            r += er; g += eg; b += eb; energy += .2126 * er + .7152 * eg + .0722 * eb;
            if (w > 1f / 255) covered++;
        }
        if (energy <= 0) return RtMaterialDesc.EmissionSummary.NONE;
        float inv = 1f / mask.length;
        return new RtMaterialDesc.EmissionSummary((float) r * inv, (float) g * inv, (float) b * inv,
                (float) energy * inv, covered * inv);
    }

    private static final class StatsAccumulator {
        final int width, height; final EmissionFootprint.Builder footprint; final OpenPbrColorBinding emissionColor;
        long sr, sg, sb, sa; double lr, lg, lb; int covered;
        StatsAccumulator(int width, int height, int resolution, OpenPbrColorBinding emissionColor) {
            this.width = width; this.height = height; this.emissionColor = emissionColor;
            footprint = new EmissionFootprint.Builder(resolution, width, height);
        }
        void add(int x, int y, int pixel) {
            int a = alpha(pixel), r = red(pixel), g = green(pixel), b = blue(pixel);
            sr += r; sg += g; sb += b; sa += a; float c = a / 255f;
            float pr = (emissionColor == OpenPbrColorBinding.BASE_COLOR ? RtMaterialTextureData.srgbToLinear(r) : 1) * c;
            float pg = (emissionColor == OpenPbrColorBinding.BASE_COLOR ? RtMaterialTextureData.srgbToLinear(g) : 1) * c;
            float pb = (emissionColor == OpenPbrColorBinding.BASE_COLOR ? RtMaterialTextureData.srgbToLinear(b) : 1) * c;
            lr += pr; lg += pg; lb += pb; footprint.add(x, y, pr, pg, pb, c); if (a > 1) covered++;
        }
        AlbedoStats finish() {
            float inv = 1f / (width * (float) height), scale = inv / 255f;
            double luminance = .2126 * lr + .7152 * lg + .0722 * lb;
            RtMaterialDesc.EmissionSummary uniform = luminance <= 0 ? RtMaterialDesc.EmissionSummary.NONE
                    : new RtMaterialDesc.EmissionSummary((float) lr * inv, (float) lg * inv,
                    (float) lb * inv, (float) luminance * inv, covered * inv);
            return new AlbedoStats(sr * scale, sg * scale, sb * scale, sa * scale, uniform, footprint.build());
        }
    }

    private static int sample(MaterialTextureImage image, int x, int y, int width, int height) {
        return image.albedoArgb(Math.min(image.width() - 1, x * image.width() / width),
                Math.min(image.height() - 1, y * image.height() / height));
    }
    private static int alpha(int argb) { return argb >>> 24; }
    private static int red(int argb) { return argb >>> 16 & 255; }
    private static int green(int argb) { return argb >>> 8 & 255; }
    private static int blue(int argb) { return argb & 255; }
}
