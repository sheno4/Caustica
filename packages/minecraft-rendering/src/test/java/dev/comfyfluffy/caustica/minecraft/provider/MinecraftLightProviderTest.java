package dev.comfyfluffy.caustica.minecraft.provider;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.light.MinecraftTerrainLightBatch;
import dev.comfyfluffy.caustica.minecraft.light.MinecraftTerrainLightSnapshot;
import dev.comfyfluffy.caustica.minecraft.MinecraftCelestialFrame;
import dev.comfyfluffy.caustica.minecraft.MinecraftLightFrame;
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
        MinecraftTerrainLightBatch section = new MinecraftTerrainLightBatch(9L, 1L, List.of(rectangle));

        provider.publish(emptyCelestial(), Optional.empty(),
                new MinecraftTerrainLightSnapshot(List.of(section), 1L));
        assertEquals(4, channel.issued);
        assertEquals(4, channel.submissions.getLast().operations().size());

        int submissions = channel.submissions.size();
        provider.publish(emptyCelestial(), Optional.empty(),
                new MinecraftTerrainLightSnapshot(List.of(section), 2L));
        assertEquals(4, channel.issued);
        assertEquals(submissions, channel.submissions.size());

        provider.publish(emptyCelestial(), Optional.empty(), MinecraftTerrainLightSnapshot.empty(3L));
        assertEquals(1, channel.submissions.getLast().operations().size());
        assertTrue(channel.submissions.getLast().operations().getLast() instanceof LightChannel.DropLight);
    }

    @Test
    void unchangedCelestialHelmetAndTerrainSkipSubmission() {
        RecordingLights channel = new RecordingLights();
        MinecraftLightProvider provider = provider(channel);
        var celestial = MinecraftLightProvider.celestialLights(frame(0.0, Math.PI, 128_000, 5, 0));
        var helmet = Optional.of(helmet());
        var terrain = MinecraftTerrainLightSnapshot.empty(1L);

        provider.publish(celestial, helmet, terrain);
        provider.publish(celestial, helmet, terrain);

        assertEquals(1, channel.submissions.size());
        assertEquals(3, channel.submissions.getFirst().operations().size());
    }

    @Test
    void helmetReplacementAndClearEmitOnlyTheirChangedOperation() {
        RecordingLights channel = new RecordingLights();
        MinecraftLightProvider provider = provider(channel);
        var terrain = MinecraftTerrainLightSnapshot.empty(1L);
        LightDescriptor.Spot first = helmet();
        LightDescriptor.Spot replacement = new LightDescriptor.Spot(
                1, 2, 3, 0, -1, 0, 12, 0.5, 4, 5, 7);
        provider.publish(emptyCelestial(), Optional.of(first), terrain);

        provider.publish(emptyCelestial(), Optional.of(replacement), terrain);
        assertEquals(1, channel.submissions.getLast().operations().size());
        assertTrue(channel.submissions.getLast().operations().getFirst() instanceof LightChannel.SetLight);

        provider.publish(emptyCelestial(), Optional.empty(), terrain);
        assertEquals(1, channel.submissions.getLast().operations().size());
        assertTrue(channel.submissions.getLast().operations().getFirst() instanceof LightChannel.DropLight);
    }

    @Test
    void celestialTransitionsEmitOnlyTheChangedSunOrMoonOperation() {
        RecordingLights channel = new RecordingLights();
        MinecraftLightProvider provider = provider(channel);
        var terrain = MinecraftTerrainLightSnapshot.empty(1L);
        provider.publish(MinecraftLightProvider.celestialLights(frame(
                0.0, Math.PI, 128_000, 5, 0)), Optional.empty(), terrain);

        provider.publish(emptyCelestial(), Optional.empty(), terrain);
        assertEquals(1, channel.submissions.getLast().operations().size());
        assertTrue(channel.submissions.getLast().operations().getFirst() instanceof LightChannel.DropLight);

        provider.publish(MinecraftLightProvider.celestialLights(frame(
                Math.PI, 0.0, 128_000, 5, 0)), Optional.empty(), terrain);
        assertEquals(1, channel.submissions.getLast().operations().size());
        assertTrue(channel.submissions.getLast().operations().getFirst() instanceof LightChannel.SetLight);
    }

    @Test
    void closeClearsSingletonAndTerrainLightsInOneBatch() {
        RecordingLights channel = new RecordingLights();
        MinecraftLightProvider provider = provider(channel);
        LightDescriptor.Rectangle rectangle = new LightDescriptor.Rectangle(
                1, 2, 3, 0.5, 0, 0, 0, 0.5, 0, 4, 5, 6);
        provider.publish(emptyCelestial(), Optional.empty(), new MinecraftTerrainLightSnapshot(
                List.of(new MinecraftTerrainLightBatch(9L, 1L, List.of(rectangle))), 1L));

        provider.close();

        var clear = channel.submissions.getLast().operations();
        assertEquals(4, clear.size());
        assertTrue(clear.stream().allMatch(LightChannel.DropLight.class::isInstance));
    }

    @Test
    void updateConsumesOneCoherentCapturedFrame() {
        RecordingLights channel = new RecordingLights();
        AtomicInteger reads = new AtomicInteger();
        var celestial = new MinecraftCelestialFrame(0, (float) Math.PI, 0, 0,
                0, 63, 63, 1, new MinecraftLightingCalibration(128_000, 5, 1, 0, 0, .1f));
        var frame = new MinecraftLightFrame(Optional.of(celestial), Optional.empty(),
                MinecraftTerrainLightSnapshot.empty(1));
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

    private static MinecraftLightProvider provider(RecordingLights channel) {
        return new MinecraftLightProvider(channel, new SceneId() { },
                () -> new MinecraftLightProvider.CelestialSettings(30.0, 0.6, 1.5), () -> null);
    }

    private static LightDescriptor.Spot helmet() {
        return new LightDescriptor.Spot(1, 2, 3, 0, -1, 0, 10, 0.5, 4, 5, 6);
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
