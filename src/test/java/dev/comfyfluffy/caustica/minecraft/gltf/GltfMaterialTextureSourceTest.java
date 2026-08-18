package dev.comfyfluffy.caustica.minecraft.gltf;

import dev.comfyfluffy.caustica.api.provider.OpenPbrTextureTexel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GltfMaterialTextureSourceTest {
    @Test
    void exposesRawSurfaceChannelsAndIndependentLinearEmissionRgb() throws Exception {
        var mr = new GltfMaterialTextureSource.ImageData(1, 1, new int[]{0xff004080});
        var emissive = new GltfMaterialTextureSource.ImageData(1, 1, new int[]{0xff804020});
        var source = new GltfMaterialTextureSource(mr, null, emissive, 1, 1, 1.33f, 1.0f);
        OpenPbrTextureTexel texel = new OpenPbrTextureTexel();

        try (var image = source.open()) {
            assertEquals(0xffffffff, image.albedoArgb(0, 0));
            assertEquals(0xffffffff, image.alphaArgb(0, 0, 0));
            image.readOpenPbr(0, 0, texel);
        }

        assertEquals(64.0f / 255.0f, texel.specularRoughness);
        assertEquals(128.0f / 255.0f, texel.baseMetalness);
        assertEquals(1.33f, texel.specularIor);
        assertEquals(1.0f, texel.emissionWeight);
        assertEquals(srgbToLinear(128), texel.emissionColorR);
        assertEquals(srgbToLinear(64), texel.emissionColorG);
        assertEquals(srgbToLinear(32), texel.emissionColorB);
    }

    private static float srgbToLinear(int channel) {
        float value = channel / 255.0f;
        return value <= 0.04045f ? value / 12.92f
                : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
    }
}
