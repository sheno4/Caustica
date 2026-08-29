package dev.comfyfluffy.caustica.minecraft.provider;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.light.RetainedLightBatch;
import dev.comfyfluffy.caustica.engine.light.RetainedLightSnapshot;
import dev.comfyfluffy.caustica.minecraft.MinecraftCapturedFrame;
import dev.comfyfluffy.caustica.minecraft.MinecraftLightingCalibration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftLightProviderTest {
    @Test
    void helmetUsesTheCircularSpotContract() {
        LightDescriptor.Spot spot = MinecraftLightProvider.helmetSpot(1, 2, 3, 0, 0, -1);

        assertEquals(48.0, spot.rangeMeters());
        assertEquals(Math.toRadians(22.0), spot.halfAngleRadians());
        assertEquals(-1.0, spot.directionZ());
    }

    @Test
    void noonSubmitsOnlyTheAboveHorizonSun() {
        MinecraftLightProvider.CelestialLights lights = MinecraftLightProvider.celestialLights(frame(
                0.0, Math.PI, 128_000, 5, 0));

        assertTrue(lights.sun().isPresent());
        assertTrue(lights.moon().isEmpty());
        assertEquals(128_000, lights.sun().orElseThrow().illuminanceRedLux());
        assertEquals(Math.cos(Math.PI / 6.0), lights.sun().orElseThrow().directionY(), 1.0e-12);
    }

    @Test
    void nightSubmitsPhaseScaledMoonAndOmitsZeroMoon() {
        MinecraftLightProvider.CelestialLights fullMoon = MinecraftLightProvider.celestialLights(frame(
                Math.PI, 0.0, 128_000, 5, 0));
        MinecraftLightProvider.CelestialLights zeroMoon = MinecraftLightProvider.celestialLights(frame(
                Math.PI, 0.0, 128_000, 0, 4));

        assertTrue(fullMoon.sun().isEmpty());
        assertEquals(5.0, fullMoon.moon().orElseThrow().illuminanceRedLux());
        assertTrue(zeroMoon.sun().isEmpty());
        assertTrue(zeroMoon.moon().isEmpty());
    }

    @Test
    void newMoonRetainsTheConfiguredFixedFraction() {
        MinecraftLightProvider.CelestialLights lights = MinecraftLightProvider.celestialLights(frame(
                Math.PI, 0.0, 128_000, 5, 4));

        assertEquals(0.5, lights.moon().orElseThrow().illuminanceRedLux(), 1.0e-12);
    }

    @Test
    void terrainLightsKeepIssuedIdsUntilTheirSectionDisappears() {
        RecordingLights channel = new RecordingLights();
        MinecraftLightProvider provider = new MinecraftLightProvider(channel, new SceneId() { },
                () -> new MinecraftLightProvider.CelestialSettings(30.0, 0.6, 1.5), () -> null);
        LightDescriptor.Rectangle rectangle = new LightDescriptor.Rectangle(
                1, 2, 3, 0.5, 0, 0, 0, 0.5, 0, 4, 5, 6);
        RetainedLightBatch section = new RetainedLightBatch(9L, 1L, List.of(rectangle));

        provider.publish(emptyCelestial(), Optional.empty(),
                new RetainedLightSnapshot(List.of(section), 1L));
        assertEquals(4, channel.issued);
        assertEquals(4, channel.submissions.getLast().operations().size());

        provider.publish(emptyCelestial(), Optional.empty(),
                new RetainedLightSnapshot(List.of(section), 2L));
        assertEquals(4, channel.issued);
        assertEquals(3, channel.submissions.getLast().operations().size());

        provider.publish(emptyCelestial(), Optional.empty(), RetainedLightSnapshot.empty(3L));
        assertEquals(4, channel.submissions.getLast().operations().size());
        assertTrue(channel.submissions.getLast().operations().getLast() instanceof LightChannel.DropLight);
    }

    @Test
    void updateConsumesOneCoherentCapturedFrame() {
        RecordingLights channel = new RecordingLights();
        AtomicInteger reads = new AtomicInteger();
        var celestial = new MinecraftCapturedFrame.Celestial(0, (float) Math.PI, 0, 0,
                0, 63, 63, 1, new MinecraftLightingCalibration(128_000, 5, 1, 0, 0, .1f));
        var frame = new MinecraftCapturedFrame(Optional.of(celestial), Optional.empty(), Optional.empty(),
                RetainedLightSnapshot.empty(1));
        MinecraftLightProvider provider = new MinecraftLightProvider(channel, new SceneId() { },
                () -> new MinecraftLightProvider.CelestialSettings(30, .6, 1.5),
                () -> { reads.incrementAndGet(); return frame; });

        provider.update();

        assertEquals(1, reads.get());
        assertEquals(3, channel.submissions.getLast().operations().size());
        assertTrue(channel.submissions.getLast().operations().getFirst() instanceof LightChannel.SetLight);
    }

    private static MinecraftLightProvider.CelestialLights emptyCelestial() {
        return new MinecraftLightProvider.CelestialLights(Optional.empty(), Optional.empty());
    }

    private static MinecraftLightProvider.CelestialFrame frame(
            double sunAngle, double moonAngle, double sunLux, double moonLux, int moonPhase) {
        return new MinecraftLightProvider.CelestialFrame(sunAngle, moonAngle, Math.PI / 6.0,
                sunLux, moonLux, moonPhase, 0.1, Math.toRadians(0.6), Math.toRadians(1.5));
    }

    private static final class RecordingLights implements LightChannel {
        private int issued;
        private final ArrayList<RetainedBatch<Operation>> submissions = new ArrayList<>();

        @Override
        public LightId newLight() {
            issued++;
            return new LightId() { };
        }

        @Override
        public void submit(RetainedBatch<Operation> batch) {
            submissions.add(batch);
        }
    }
}
