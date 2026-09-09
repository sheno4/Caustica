package dev.comfyfluffy.caustica.minecraft.content.material;

/** Scans canonical Minecraft pixels into the fixed emission footprints used by terrain light derivation. */
public final class MinecraftMaterialEmissionAnalyzer {
    static final int FOOTPRINT_RESOLUTION = 16;

    public record Scan(MinecraftEmissionFootprint masked, MinecraftEmissionFootprint uniform) {
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
                    int pixel = image.albedoArgb(x, y);
                    float alpha = (pixel >>> 24) / 255.0f;
                    float baseR = usesBaseColor ? linear(pixel >>> 16 & 255) : 1.0f;
                    float baseG = usesBaseColor ? linear(pixel >>> 8 & 255) : 1.0f;
                    float baseB = usesBaseColor ? linear(pixel & 255) : 1.0f;
                    uniform.add(x, y, baseR * alpha, baseG * alpha, baseB * alpha, alpha);

                    texel.reset();
                    image.readOpenPbr(x, y, texel);
                    float weight = Math.clamp(texel.emissionWeight, 0.0f, 1.0f) * alpha;
                    float emissionR = texel.emissionColorR * baseR;
                    float emissionG = texel.emissionColorG * baseG;
                    float emissionB = texel.emissionColorB * baseB;
                    masked.add(x, y, emissionR * weight, emissionG * weight,
                            emissionB * weight, weight);
                }
            }
            return new Scan(masked.build(), uniform.build());
        }
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

    private static float linear(int channel) {
        float value = channel / 255.0f;
        return value <= 0.04045f ? value / 12.92f
                : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
    }
}
