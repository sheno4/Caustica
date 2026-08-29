package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ProgramBuilder;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeDefinition;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShowcaseProgramsTest {
    @Test
    void publishesReadinessWithoutWaitingForTheCompletionCallback() {
        CapturePrograms channel = new CapturePrograms();
        ShowcasePrograms programs = new ShowcasePrograms(channel, message -> { });

        assertFalse(programs.ready());
        channel.complete(new ProgramRegistration.Ready());
        assertTrue(programs.ready());
    }

    @Test
    void acceptsDottedSlangTypesWhenOpeningTheShowcasePrograms() throws Exception {
        CapturePrograms channel = new CapturePrograms();

        ShowcasePrograms programs = new ShowcasePrograms(channel, message -> { });

        assertEquals(List.of(
                "api_showcase.OpaqueSurface",
                "api_showcase.TexturedSurface"),
                channel.surfaces.stream().map(value -> value.surface().type()).toList());
        assertEquals("api_showcase.TextureCoverage", channel.surfaces.getLast().coverage().type());
        assertEquals("api_showcase.AbsorbingVolume", channel.volumes.getFirst().implementation().type());
        assertEquals("api_showcase.GradientEnvironment",
                channel.environments.getFirst().implementation().type());
        for (SurfaceDefinition<?, ?> surface : channel.surfaces) {
            try (var module = surface.surface().source().openModule(surface.surface().module())) {
                assertTrue(module != null);
            }
        }

        programs.close();
        assertTrue(channel.closed);
    }

    private static final class CapturePrograms implements ProgramChannel, ProgramBuilder {
        private final List<SurfaceDefinition<?, ?>> surfaces = new ArrayList<>();
        private final List<VolumeDefinition<?, ?>> volumes = new ArrayList<>();
        private final List<EnvironmentDefinition<?>> environments = new ArrayList<>();
        private Consumer<ProgramRegistration.Completion> completionCallback;
        private boolean closed;

        @Override
        public <E> ProgramRegistration<E> register(Function<? super ProgramBuilder, ? extends E> declaration) {
            E exports = declaration.apply(this);
            return new ProgramRegistration<>() {
                @Override public E exports() { return exports; }
                @Override public void whenComplete(Consumer<? super Completion> callback) {
                    completionCallback = callback::accept;
                }
                @Override public void close() { closed = true; }
            };
        }

        private void complete(ProgramRegistration.Completion completion) {
            completionCallback.accept(completion);
        }

        @Override
        public <B, N> SurfaceId<B, N> surface(SurfaceDefinition<B, N> definition) {
            surfaces.add(definition);
            return new SurfaceId<>() { };
        }

        @Override
        public <B, N> VolumeId<B, N> volume(VolumeDefinition<B, N> definition) {
            volumes.add(definition);
            return new VolumeId<>() { };
        }

        @Override
        public <B> EnvironmentId<B> environment(EnvironmentDefinition<B> definition) {
            environments.add(definition);
            return new EnvironmentId<>() { };
        }
    }
}
