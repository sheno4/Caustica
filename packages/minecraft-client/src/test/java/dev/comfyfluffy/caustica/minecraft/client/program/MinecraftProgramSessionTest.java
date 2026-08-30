package dev.comfyfluffy.caustica.minecraft.client.program;

import dev.comfyfluffy.caustica.minecraft.rendering.program.MinecraftPrograms;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ProgramBuilder;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeDefinition;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftTelemetry;
import dev.comfyfluffy.caustica.minecraft.rendering.provider.MinecraftLightProvider;
import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.SkyLutPass;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionValues;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftProgramSessionTest {
    @Test
    void preparedEpochRegistersProgramsBeforeItsUploadPassRecords() {
        CapturingChannel channel = new CapturingChannel();
        List<String> events = new ArrayList<>();
        var roots = new MinecraftProgramSession.Roots(
                MinecraftProgramTypes.IMPLEMENTATION_DATA.data(11L),
                MinecraftProgramTypes.PRIMITIVE_DATA.data(22L),
                MinecraftProgramTypes.INSTANCE_DATA.data(33L), () -> { });
        Pass<PassFrame> upload = new Pass<>() {
            @Override public void record(PassFrame frame) { events.add("upload"); }
            @Override public void close() { }
        };

        MinecraftProgramSession.registerPrograms(channel, roots);
        events.add("registered");
        upload.record(null);

        assertEquals(1, channel.registrations);
        assertEquals(List.of("registered", "upload"), events);
    }

    @Test
    void declaresOneAtomicSetWithNonzeroRootsAndOneEpochRetirement() {
        CapturingChannel channel = new CapturingChannel();
        AtomicInteger retirements = new AtomicInteger();
        var roots = new MinecraftProgramSession.Roots(
                MinecraftProgramTypes.IMPLEMENTATION_DATA.data(11L),
                MinecraftProgramTypes.PRIMITIVE_DATA.data(22L),
                MinecraftProgramTypes.INSTANCE_DATA.data(33L), retirements::incrementAndGet);

        ProgramRegistration<MinecraftPrograms> registration =
                MinecraftProgramSession.registerPrograms(channel, roots);

        assertEquals(1, channel.registrations);
        assertEquals(List.of("caustica_minecraft_surface", "caustica_water_surface",
                        "caustica_portal_surface"),
                channel.surfaces.stream().map(value -> value.surface().module()).toList());
        assertTrue(channel.surfaces.stream().allMatch(value -> value.implementationData().bits() == 11L));
        assertEquals(11L, channel.volumes.getFirst().implementationData().bits());
        assertEquals("caustica_minecraft_overworld_sky",
                channel.environments.getFirst().implementation().module());
        assertSame(channel.surfaces.getFirst().retired(), roots.retirement());
        channel.surfaces.forEach(value -> value.retired().run());
        channel.volumes.forEach(value -> value.retired().run());
        assertEquals(1, retirements.get());
        assertSame(registration.exports().environment(), channel.environmentId);
    }

    @Test
    void rejectsZeroFallbackOrImplementationRoots() {
        assertThrows(IllegalArgumentException.class, () -> new MinecraftProgramSession.Roots(
                MinecraftProgramTypes.IMPLEMENTATION_DATA.data(0L),
                MinecraftProgramTypes.PRIMITIVE_DATA.data(2L),
                MinecraftProgramTypes.INSTANCE_DATA.data(3L), () -> { }));
    }

    @Test
    void displacedProgramsAreReleasedOnlyAfterTheReplacementSkyRecordsSuccessfully() {
        AtomicInteger delegateRecords = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        Pass<PassFrame> delegate = new Pass<>() {
            @Override public void record(PassFrame frame) { delegateRecords.incrementAndGet(); }
            @Override public void close() { }
        };
        var pass = new MinecraftProgramSession.FirstRecordPass(delegate, releases::incrementAndGet);

        pass.record(null);
        pass.record(null);

        assertEquals(2, delegateRecords.get());
        assertEquals(1, releases.get());
    }

    @Test
    void failedReplacementSkyDoesNotReleaseTheLiveProgram() {
        AtomicInteger releases = new AtomicInteger();
        Pass<PassFrame> delegate = new Pass<>() {
            @Override public void record(PassFrame frame) { throw new IllegalStateException("sky failed"); }
            @Override public void close() { }
        };
        var pass = new MinecraftProgramSession.FirstRecordPass(delegate, releases::incrementAndGet);

        assertThrows(IllegalStateException.class, () -> pass.record(null));
        assertEquals(0, releases.get());
    }

    @Test
    void celestialSettingsComeFromTheInjectedFeatureValues() {
        OptionValues values = new OptionValues() {
            @Override @SuppressWarnings("unchecked") public <T> T get(Option<T> option) {
                Object value = option == SkyLutPass.SUN_NOON_SOUTH_TILT_DEGREES ? 11.0f
                        : option == SkyLutPass.SUN_ANGULAR_RADIUS_DEGREES ? 0.3f : 0.7f;
                return (T) value;
            }
        };

        var settings = MinecraftProgramSession.celestialSettings(values);

        assertEquals(11.0, settings.noonTiltDegrees());
        assertEquals(0.3, settings.sunAngularRadiusDegrees(), 1.0e-6);
        assertEquals(0.7, settings.moonAngularRadiusDegrees(), 1.0e-6);
    }

    @Test
    void lightUpdateTimingBracketsTheCapturedFrameUpdate() {
        List<String> events = new ArrayList<>();
        MinecraftLightProvider lights = new MinecraftLightProvider(new NoopLightChannel(), new SceneId() { },
                () -> new MinecraftLightProvider.CelestialSettings(30, 0.6, 1.5),
                () -> { events.add("update"); return null; });
        var pass = new MinecraftProgramSession.LightUpdatePass(lights,
                new RecordingInstrumentation(events));

        pass.record(null);

        assertEquals(List.of("start", "update", "end:terrain.lightScenePublish:17"), events);
    }

    private static final class CapturingChannel implements ProgramChannel, ProgramBuilder {
        final List<SurfaceDefinition<?, ?>> surfaces = new ArrayList<>();
        final List<VolumeDefinition<?, ?>> volumes = new ArrayList<>();
        final List<EnvironmentDefinition<?>> environments = new ArrayList<>();
        final EnvironmentId<?> environmentId = new EnvironmentId<>() { };
        int registrations;

        @Override public <E> ProgramRegistration<E> register(
                java.util.function.Function<? super ProgramBuilder, ? extends E> declaration) {
            registrations++;
            E exports = declaration.apply(this);
            return new ProgramRegistration<>() {
                @Override public E exports() { return exports; }
                @Override public void whenComplete(
                        java.util.function.Consumer<? super Completion> callback) { }
                @Override public void close() { }
            };
        }
        @Override public <B, N> SurfaceId<B, N> surface(SurfaceDefinition<B, N> definition) {
            surfaces.add(definition);
            return new SurfaceId<>() { };
        }
        @Override public <B, N> VolumeId<B, N> volume(VolumeDefinition<B, N> definition) {
            volumes.add(definition);
            return new VolumeId<>() { };
        }
        @SuppressWarnings("unchecked")
        @Override public <B> EnvironmentId<B> environment(EnvironmentDefinition<B> definition) {
            environments.add(definition);
            return (EnvironmentId<B>) environmentId;
        }
    }

    private static final class NoopLightChannel implements LightChannel {
        @Override public LightId newLight() { return new LightId() { }; }
        @Override public void submit(RetainedBatch<Operation> batch) { }
    }

    private record RecordingInstrumentation(List<String> events)
            implements MinecraftTelemetry.Instrumentation {
        @Override public boolean enabled() { return true; }
        @Override public long frameSerial() { return 0; }
        @Override public long startStage() { events.add("start"); return 17; }
        @Override public void endStage(String name, long startedNanos) {
            events.add("end:" + name + ':' + startedNanos);
        }
        @Override public void count(String name, long delta) { }
        @Override public void set(String name, long value) { }
        @Override public void max(String name, long value) { }
        @Override public Object extraction(MinecraftTelemetry.GeometrySource source, int geometryCount) {
            return null;
        }
        @Override public void published(Object stamp) { }
        @Override public void afterPublicationVisible(LongConsumer action) { }
    }

}
