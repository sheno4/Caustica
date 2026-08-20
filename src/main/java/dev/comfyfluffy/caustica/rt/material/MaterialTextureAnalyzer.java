package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.api.provider.MaterialTextureAnalysisSource;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureImage;
import dev.comfyfluffy.caustica.api.provider.OpenPbrColorBinding;
import dev.comfyfluffy.caustica.api.provider.OpenPbrTextureTexel;

import java.util.List;

/** Decodes source images into canonical page levels and the albedo average used by material bindings. */
final class MaterialTextureAnalyzer {
    record Alpha(float[] texels, float minAlpha, float maxAlpha) { }

    record AlbedoStats(float averageR, float averageG, float averageB, float averageA) {
        static final AlbedoStats NEUTRAL = new AlbedoStats(1, 1, 1, 0);
    }

    record Decoded(List<RtMaterialTextureData.Level> levels, AlbedoStats stats) { }

    private MaterialTextureAnalyzer() { }

    static Decoded decode(MaterialTextureAnalysisSource source,
                          OpenPbrColorBinding emissionColorBinding, int maxLod) throws Exception {
        try (MaterialTextureImage texture = source.texture().open()) {
            int width = source.width(), height = source.height();
            float[] surface0 = new float[width * height * 4];
            float[] normal = new float[surface0.length];
            float[] surface1 = new float[surface0.length];
            float[] emissionColor = new float[surface0.length];
            boolean emissionUsesBase = emissionColorBinding == OpenPbrColorBinding.BASE_COLOR;
            StatsAccumulator stats = new StatsAccumulator(width, height);
            OpenPbrTextureTexel texel = new OpenPbrTextureTexel();
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                int pixel = sample(texture, x, y, width, height);
                stats.add(pixel);
                float r = RtMaterialTextureData.srgbToLinear(red(pixel));
                float g = RtMaterialTextureData.srgbToLinear(green(pixel));
                float b = RtMaterialTextureData.srgbToLinear(blue(pixel));
                texel.reset();
                texture.readOpenPbr(x, y, texel);
                emissionColor[i] = texel.emissionColorR * (emissionUsesBase ? r : 1);
                emissionColor[i + 1] = texel.emissionColorG * (emissionUsesBase ? g : 1);
                emissionColor[i + 2] = texel.emissionColorB * (emissionUsesBase ? b : 1);
                emissionColor[i + 3] = 1.0f;
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
            }
            return new Decoded(RtMaterialTextureData.mipChain(new RtMaterialTextureData.Level(width, height,
                    surface0, normal, surface1, emissionColor), maxLod), stats.finish());
        }
    }

    static Alpha scanAlpha(MaterialTextureImage texture, int width, int height, int alphaFrameCount) {
        if (alphaFrameCount <= 0) throw new IllegalArgumentException("Material texture has no alpha frames");
        float[] texels = new float[width * height * 4];
        int materialMin = 255, materialMax = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int min = 255, max = 0;
            for (int frame = 0; frame < alphaFrameCount; frame++) {
                int value = alpha(texture.alphaArgb(frame, x, y));
                min = Math.min(min, value); max = Math.max(max, value);
            }
            int i = (y * width + x) * 4;
            texels[i] = min / 255f; texels[i + 1] = max / 255f;
            materialMin = Math.min(materialMin, min); materialMax = Math.max(materialMax, max);
        }
        return new Alpha(texels, materialMin / 255f, materialMax / 255f);
    }

    static Alpha scanAlpha(MaterialTextureAnalysisSource source) throws Exception {
        try (MaterialTextureImage texture = source.texture().open()) {
            return scanAlpha(texture, source.width(), source.height(), source.alphaFrameCount());
        }
    }

    static boolean hasTemporalVariation(Alpha alpha) {
        for (int i = 0; i < alpha.texels.length; i += 4) if (alpha.texels[i] != alpha.texels[i + 1]) return true;
        return false;
    }

    static AlbedoStats scanAlbedo(MaterialTextureAnalysisSource source) throws Exception {
        try (MaterialTextureImage image = source.texture().open()) {
            StatsAccumulator stats = new StatsAccumulator(source.width(), source.height());
            for (int y = 0; y < source.height(); y++) for (int x = 0; x < source.width(); x++)
                stats.add(sample(image, x, y, source.width(), source.height()));
            return stats.finish();
        }
    }

    private static final class StatsAccumulator {
        final int width, height;
        long sr, sg, sb, sa;
        StatsAccumulator(int width, int height) {
            this.width = width;
            this.height = height;
        }
        void add(int pixel) {
            int a = alpha(pixel), r = red(pixel), g = green(pixel), b = blue(pixel);
            sr += r; sg += g; sb += b; sa += a;
        }
        AlbedoStats finish() {
            float scale = 1.0f / (width * (float) height * 255.0f);
            return new AlbedoStats(sr * scale, sg * scale, sb * scale, sa * scale);
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
