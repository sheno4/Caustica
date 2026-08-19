package dev.comfyfluffy.caustica.minecraft.provider;

import dev.comfyfluffy.caustica.api.provider.LightDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftLightProviderTest {
    @Test
    void noonSubmitsOnlyTheAboveHorizonSun() {
        List<LightDescriptor.Distant> lights = MinecraftLightProvider.celestialLights(frame(
                0.0, Math.PI, 128_000, 5, 0));

        assertEquals(1, lights.size());
        assertEquals(2, lights.getFirst().key());
        assertEquals(128_000, lights.getFirst().illuminanceRedLux());
        assertEquals(Math.cos(Math.PI / 6.0), lights.getFirst().directionY(), 1.0e-12);
    }

    @Test
    void nightSubmitsPhaseScaledMoonAndOmitsZeroMoon() {
        List<LightDescriptor.Distant> fullMoon = MinecraftLightProvider.celestialLights(frame(
                Math.PI, 0.0, 128_000, 5, 0));
        List<LightDescriptor.Distant> zeroMoon = MinecraftLightProvider.celestialLights(frame(
                Math.PI, 0.0, 128_000, 0, 4));

        assertEquals(1, fullMoon.size());
        assertEquals(3, fullMoon.getFirst().key());
        assertEquals(5.0, fullMoon.getFirst().illuminanceRedLux());
        assertEquals(List.of(), zeroMoon);
    }

    @Test
    void newMoonRetainsTheConfiguredFixedFraction() {
        List<LightDescriptor.Distant> lights = MinecraftLightProvider.celestialLights(frame(
                Math.PI, 0.0, 128_000, 5, 4));

        assertEquals(0.5, lights.getFirst().illuminanceRedLux(), 1.0e-12);
    }

    private static MinecraftLightProvider.CelestialFrame frame(
            double sunAngle, double moonAngle, double sunLux, double moonLux, int moonPhase) {
        return new MinecraftLightProvider.CelestialFrame(sunAngle, moonAngle, Math.PI / 6.0,
                sunLux, moonLux, moonPhase, 0.1, Math.toRadians(0.6), Math.toRadians(1.5));
    }
}
