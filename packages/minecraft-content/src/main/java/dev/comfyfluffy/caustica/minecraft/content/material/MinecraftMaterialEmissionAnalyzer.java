package dev.comfyfluffy.caustica.minecraft.content.material;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;

/** Scans canonical Minecraft pixels into the fixed emission footprints used by terrain light derivation. */
public final class MinecraftMaterialEmissionAnalyzer {
    static final int FOOTPRINT_RESOLUTION = 16;

    public record Scan(MinecraftEmissionFootprint masked, MinecraftEmissionFootprint uniform,
                       MeshBuild.OpacityMicromapHint opacityHint) {
    }

    private MinecraftMaterialEmissionAnalyzer() {
    }

    public static Scan scan(MaterialTextureResource resource) throws Exception {
        MaterialTextureAnalysisSource source = resource.analysisSource();
        try (MaterialTextureImage image = source.texture().open()) {
            MinecraftEmissionFootprint.Builder masked = new MinecraftEmissionFootprint.Builder(
                    FOOTPRINT_RESOLUTION, source.width(), source.height());
            MinecraftEmissionFootprint.Builder uniform = new MinecraftEmissionFootprint.Builder(
                    FOOTPRINT_RESOLUTION, source.width(), source.height());
            OpenPbrTextureTexel texel = new OpenPbrTextureTexel();
            boolean usesBaseColor = resource.emissionColorBinding() == OpenPbrColorBinding.BASE_COLOR;
            for (int y = 0; y < source.height(); y++) {
                for (int x = 0; x < source.width(); x++) {
                    int pixel = sample(image, x, y, source.width(), source.height());
                    float alpha = (pixel >>> 24) / 255.0f;
                    float baseR = linear(pixel >>> 16 & 255);
                    float baseG = linear(pixel >>> 8 & 255);
                    float baseB = linear(pixel & 255);
                    uniform.add(x, y, (usesBaseColor ? baseR : 1.0f) * alpha,
                            (usesBaseColor ? baseG : 1.0f) * alpha,
                            (usesBaseColor ? baseB : 1.0f) * alpha, alpha);

                    texel.reset();
                    image.readOpenPbr(x, y, texel);
                    float weight = Math.clamp(texel.emissionWeight, 0.0f, 1.0f) * alpha;
                    float emissionR = texel.emissionColorR * (usesBaseColor ? baseR : 1.0f);
                    float emissionG = texel.emissionColorG * (usesBaseColor ? baseG : 1.0f);
                    float emissionB = texel.emissionColorB * (usesBaseColor ? baseB : 1.0f);
                    masked.add(x, y, emissionR * weight, emissionG * weight,
                            emissionB * weight, weight);
                }
            }
            return new Scan(masked.build(), uniform.build(), scanAlpha(source, image));
        }
    }

    private static MeshBuild.OpacityMicromapHint scanAlpha(
            MaterialTextureAnalysisSource source, MaterialTextureImage image) {
        if (source.alphaFrameCount() == 0) return null;
        int minimum = 255;
        int maximum = 0;
        for (int frame = 0; frame < source.alphaFrameCount(); frame++) {
            for (int y = 0; y < source.height(); y++) {
                for (int x = 0; x < source.width(); x++) {
                    int alpha = image.alphaArgb(frame, x, y) >>> 24;
                    minimum = Math.min(minimum, alpha);
                    maximum = Math.max(maximum, alpha);
                }
            }
        }
        return new MeshBuild.OpacityMicromapHint(minimum / 255.0f, maximum / 255.0f, 4);
    }

    static MinecraftEmissionFootprint constant(float r, float g, float b) {
        MinecraftEmissionFootprint.Builder builder = new MinecraftEmissionFootprint.Builder(
                FOOTPRINT_RESOLUTION, FOOTPRINT_RESOLUTION, FOOTPRINT_RESOLUTION);
        for (int y = 0; y < FOOTPRINT_RESOLUTION; y++) {
            for (int x = 0; x < FOOTPRINT_RESOLUTION; x++) {
                builder.add(x, y, r, g, b, 1.0f);
            }
        }
        return builder.build();
    }

    private static int sample(MaterialTextureImage image, int x, int y, int width, int height) {
        return image.albedoArgb(Math.min(image.width() - 1, x * image.width() / width),
                Math.min(image.height() - 1, y * image.height() / height));
    }

    private static float linear(int channel) {
        float value = channel / 255.0f;
        return value <= 0.04045f ? value / 12.92f
                : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
    }
}
