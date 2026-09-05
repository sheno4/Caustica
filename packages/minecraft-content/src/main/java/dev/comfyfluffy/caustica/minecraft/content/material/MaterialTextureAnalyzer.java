package dev.comfyfluffy.caustica.minecraft.content.material;

import java.util.List;

/** Decodes source images into canonical page levels. */
final class MaterialTextureAnalyzer {
    record Decoded(List<MaterialTextureLevels.Level> levels) { }

    private MaterialTextureAnalyzer() { }

    static Decoded decode(MaterialTextureAnalysisSource source, int maxLod) throws Exception {
        try (MaterialTextureImage texture = source.texture().open()) {
            int width = source.width(), height = source.height();
            float[] surface0 = new float[width * height * 4];
            float[] normal = new float[surface0.length];
            float[] surface1 = new float[surface0.length];
            float[] emissionColor = new float[surface0.length];
            OpenPbrTextureTexel texel = new OpenPbrTextureTexel();
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                texel.reset();
                texture.readOpenPbr(x, y, texel);
                emissionColor[i] = texel.emissionColorR;
                emissionColor[i + 1] = texel.emissionColorG;
                emissionColor[i + 2] = texel.emissionColorB;
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

}
