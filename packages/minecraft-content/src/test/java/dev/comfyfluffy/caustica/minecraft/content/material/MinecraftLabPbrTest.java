package dev.comfyfluffy.caustica.minecraft.content.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftLabPbrTest {
    private static final float EPS = 1.0e-5f;

    @Test
    void decodesDielectricAndIgnoredEmission() {
        MinecraftLabPbr.Texel value = MinecraftLabPbr.decodeSpec(
                0.25f, 0.04f, 64.0f / 255.0f, 1.0f,
                0.7f, 0.6f, 0.5f);
        assertEquals(0.75f, value.specularRoughness(), EPS);
        assertEquals(0.0f, value.metalness(), EPS);
        // A dielectric authors scalar reflectance, which inverts entirely into an index.
        assertEquals(MinecraftLabPbr.iorFromF0(0.04f), value.specularIor(), EPS);
        assertEquals(1.0f, value.metalBaseColorR(), EPS);
        assertEquals(0.0f, value.emission(), EPS);
        assertEquals(0.0f, value.subsurfaceWeight(), EPS);
    }

    @Test
    void decodesGenericMetalEmissionAndThinTransmission() {
        MinecraftLabPbr.Texel value = MinecraftLabPbr.decodeSpec(
                0.5f, 1.0f, 1.0f, 127.0f / 255.0f,
                0.7f, 0.6f, 0.5f);
        assertEquals(0.5f, value.specularRoughness(), EPS);
        assertEquals(1.0f, value.metalness(), EPS);
        // Above the predefined range the albedo IS the conductor's reflectance, i.e. its base colour.
        assertEquals(0.7f, value.metalBaseColorR(), EPS);
        assertEquals(0.6f, value.metalBaseColorG(), EPS);
        assertEquals(0.5f, value.metalBaseColorB(), EPS);
        assertEquals(0.5f, value.emission(), 0.002f);
        assertEquals(1.0f, value.subsurfaceWeight(), EPS);
    }

    @Test
    void decodesPredefinedGoldWithoutUsingAlbedo() {
        MinecraftLabPbr.Texel value = MinecraftLabPbr.decodeSpec(
                1.0f, 231.0f / 255.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 0.0f);
        assertEquals(0.0f, value.specularRoughness(), EPS);
        assertEquals(1.0f, value.metalness(), EPS);
        assertEquals(0.944f, value.metalBaseColorR(), 0.002f);
        assertEquals(0.776f, value.metalBaseColorG(), 0.002f);
        assertEquals(0.373f, value.metalBaseColorB(), 0.002f);
    }

    /**
     * The roughness conversion fails silently in either direction — everything still renders, just at
     * the wrong gloss — so it is pinned against the authored smoothness values rather than checked by
     * eye. OpenPBR fixes alpha = r^2, and LabPBR authors perceptual smoothness, so r = 1 - s.
     */
    @Test
    void perceptualSmoothnessMapsToOpenPbrRoughnessAndItsSquareIsGgxAlpha() {
        float[][] smoothnessToAlpha = {
                {0.0f, 1.0f}, {0.1f, 0.81f}, {0.5f, 0.25f}, {0.9f, 0.01f}, {1.0f, 0.0f}};
        for (float[] pair : smoothnessToAlpha) {
            MinecraftLabPbr.Texel value = MinecraftLabPbr.decodeSpec(pair[0], 0.04f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f);
            assertEquals(1.0f - pair[0], value.specularRoughness(), EPS);
            float alpha = value.specularRoughness() * value.specularRoughness();
            assertEquals(pair[1], alpha, EPS);
        }
    }

    /**
     * The F0 inversion must round-trip: what the adapter stores as an index, decoded and pushed back
     * through the Fresnel relation, has to be the reflectance the pack authored.
     */
    @Test
    void authoredReflectanceRoundTripsThroughTheStoredIndex() {
        for (float f0 : new float[]{0.0f, 0.02f, 0.04f, 0.08f, 0.2f, 0.5f, 0.89f}) {
            float ior = MinecraftLabPbr.iorFromF0(f0);
            float decoded = MinecraftLabPbr.decodeIor(MinecraftLabPbr.encodeIor(ior));
            float amplitude = (decoded - 1.0f) / (decoded + 1.0f);
            assertEquals(f0, amplitude * amplitude, 1.0e-4f);
        }
    }

    @Test
    void theDefaultIndexIsTheOneThatGivesTheFamiliarFourPercent() {
        float amplitude = (OpenPbrDefaults.SPECULAR_IOR - 1.0f)
                / (OpenPbrDefaults.SPECULAR_IOR + 1.0f);
        assertEquals(0.04f, amplitude * amplitude, EPS);
    }
}
