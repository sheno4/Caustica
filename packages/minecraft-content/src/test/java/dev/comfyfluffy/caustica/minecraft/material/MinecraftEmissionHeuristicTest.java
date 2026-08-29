package dev.comfyfluffy.caustica.minecraft.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftEmissionHeuristicTest {
    @Test
    void raisesLinearLuminanceToContrastPowerAboveDarkFloor() {
        assertEquals(0.0f, MinecraftEmissionHeuristic.weight(0.09f, 0.09f, 0.09f, 1.0f));
        assertEquals(0.001f, MinecraftEmissionHeuristic.weight(0.1f, 0.1f, 0.1f, 1.0f), 1.0e-7f);
        assertEquals(0.008f, MinecraftEmissionHeuristic.weight(0.2f, 0.2f, 0.2f, 1.0f), 1.0e-6f);
    }

    @Test
    void keepsUniformLuminousMaterialsEmissive() {
        float luminance = 0.2126f * 0.35f + 0.7152f * 0.30f + 0.0722f * 0.22f;
        assertEquals(luminance * luminance * luminance,
                MinecraftEmissionHeuristic.weight(0.35f, 0.30f, 0.22f, 1.0f), 1.0e-6f);
    }

    @Test
    void rejectsUniformlyDarkInputsAndRespectsAlpha() {
        assertEquals(0.0f, MinecraftEmissionHeuristic.weight(0.01f, 0.01f, 0.01f, 1.0f));
        assertEquals(0.0f, MinecraftEmissionHeuristic.weight(0.5f, 0.4f, 0.3f, 0.0f));
        assertEquals(0.0625f, MinecraftEmissionHeuristic.weight(0.5f, 0.5f, 0.5f, 0.5f), 1.0e-6f);
    }
}
