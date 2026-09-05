package dev.comfyfluffy.caustica.minecraft.content.material;

import java.util.List;

/** Decodes source images into canonical page levels. */
final class MaterialTextureAnalyzer {
    record Decoded(List<MaterialTextureLevels.Level> levels) { }

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
            OpenPbrTextureTexel texel = new OpenPbrTextureTexel();
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                int pixel = sample(texture, x, y, width, height);
                float r = MaterialTextureLevels.srgbToLinear(red(pixel) / 255.0f);
                float g = MaterialTextureLevels.srgbToLinear(green(pixel) / 255.0f);
                float b = MaterialTextureLevels.srgbToLinear(blue(pixel) / 255.0f);
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
            return new Decoded(MaterialTextureLevels.mipChain(new MaterialTextureLevels.Level(width, height,
                    surface0, normal, surface1, emissionColor), maxLod));
        }
    }

    private static int sample(MaterialTextureImage image, int x, int y, int width, int height) {
        return image.albedoArgb(Math.min(image.width() - 1, x * image.width() / width),
                Math.min(image.height() - 1, y * image.height() / height));
    }
    private static int red(int argb) { return argb >>> 16 & 255; }
    private static int green(int argb) { return argb >>> 8 & 255; }
    private static int blue(int argb) { return argb & 255; }
}
