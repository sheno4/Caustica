package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ProgramBuilder;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.program.ProgramFailure;
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
        assertEquals(ShowcasePrograms.State.READY, programs.state());
    }

    @Test
    void reportsCompositionFailureAndKeepsTheProgramUnavailable() {
        CapturePrograms channel = new CapturePrograms();
        List<String> diagnostics = new ArrayList<>();
        ShowcasePrograms programs = new ShowcasePrograms(channel, diagnostics::add);

        channel.complete(new ProgramRegistration.Failed(
                new ProgramFailure("showcase composition failed", "surface type was not found")));

        assertFalse(programs.ready());
        assertEquals(ShowcasePrograms.State.FAILED, programs.state());
        assertEquals(List.of("showcase composition failed\nsurface type was not found"), diagnostics);
    }

    @Test
    void observesCancellationWhenTheOwnerClosesBeforePublication() {
        CapturePrograms channel = new CapturePrograms();
        ShowcasePrograms programs = new ShowcasePrograms(channel, message -> { });

        programs.close();

        assertFalse(programs.ready());
        assertEquals(ShowcasePrograms.State.CANCELLED, programs.state());
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
        assertEquals(List.of(
                        "api_showcase.OverworldSky",
                        "api_showcase.NetherSky",
                        "api_showcase.EndSky"),
                channel.environments.stream().map(value -> value.implementation().type()).toList());
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
        private boolean terminal;

        @Override
        public <E> ProgramRegistration<E> register(Function<? super ProgramBuilder, ? extends E> declaration) {
            E exports = declaration.apply(this);
            return new ProgramRegistration<>() {
                @Override public E exports() { return exports; }
                @Override public void whenComplete(Consumer<? super Completion> callback) {
                    completionCallback = callback::accept;
                }
                @Override public void close() {
                    closed = true;
                    if (!terminal && completionCallback != null) {
                        terminal = true;
                        completionCallback.accept(new Cancelled());
                    }
                }
            };
        }

        private void complete(ProgramRegistration.Completion completion) {
            terminal = true;
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
