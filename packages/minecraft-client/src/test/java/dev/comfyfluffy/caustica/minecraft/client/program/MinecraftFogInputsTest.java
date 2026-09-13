package dev.comfyfluffy.caustica.minecraft.client.program;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftFogInputsTest {
    @Test void windPhaseRepeatsAcrossPositiveAndNegativeCycleBoundaries() {
        for (double seconds : new double[]{-10, -.125, 0, 42.75, 65535.875}) {
            float phase = MinecraftFogInputs.windPhase(seconds);
            assertEquals(phase, MinecraftFogInputs.windPhase(seconds + 65536), 0.000001);
            assertEquals(phase, MinecraftFogInputs.windPhase(seconds + 65536 * 1000.0), 0.000001);
        }
    }

    @Test void windHarmonicsAdvanceSmoothlyAcrossTimeWrap() {
        double halfFrame = 1.0 / 120;
        for (int harmonic : new int[]{96, 192}) {
            double before = MinecraftFogInputs.windPhase(65536 - halfFrame) * (double) harmonic;
            double after = MinecraftFogInputs.windPhase(65536 + halfFrame) * (double) harmonic;
            double expectedAdvance = 2 * Math.PI * harmonic * (2 * halfFrame) / 65536;
            assertEquals(Math.sin(before + expectedAdvance), Math.sin(after), 0.00005);
            assertEquals(Math.cos(before + expectedAdvance), Math.cos(after), 0.00005);
        }
    }
}
