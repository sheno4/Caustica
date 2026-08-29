package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ProgramBuilder;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.program.ProgramFailure;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.program.ProgramTicket;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeDefinition;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class GltfViewerExtensionTest {
    @Test
    void minecraftFactoryUsesOneAtomicProgramSet() {
        GltfViewerExtension extension = new GltfViewerExtension();
        AtomicReference<MinecraftWorldSessionFactory> minecraftFactory = new AtomicReference<>();
        extension.registerMinecraft(new MinecraftApi(factory -> {
            minecraftFactory.set(factory);
            return () -> { };
        }));
        CaptureProgram programs = new CaptureProgram();

        ProgramRegistration<GltfProgramExports> registration = GltfViewerExtension.registerPrograms(programs);

        assertNotNull(minecraftFactory.get());
        assertEquals(1, programs.registrationCount);
        assertEquals(2, programs.surfaces.size());
        assertNotNull(programs.surfaces.getFirst().coverage());
        assertNull(programs.surfaces.getLast().coverage());
        assertNotNull(programs.surfaces.getFirst().surface().source().openModule(
                programs.surfaces.getFirst().surface().module()));
        registration.close();
        assertFalse(programs.open);
    }

    private static final class CaptureProgram implements ProgramChannel, ProgramBuilder {
        private final List<SurfaceDefinition<?, ?, ?>> surfaces = new ArrayList<>();
        private int registrationCount;
        private boolean open = true;

        @Override
        public <E> ProgramRegistration<E> register(Function<? super ProgramBuilder, ? extends E> declaration) {
            registrationCount++;
            E exports = declaration.apply(this);
            return new ProgramRegistration<>() {
                @Override public E exports() { return exports; }
                @Override public ProgramTicket readiness() { return READY; }
                @Override public void close() { open = false; }
            };
        }

        @Override public <I, B, N> SurfaceId<B, N> surface(SurfaceDefinition<I, B, N> definition) {
            surfaces.add(definition);
            return new SurfaceId<>() { };
        }
        @Override public <I, B, N> VolumeId<B, N> volume(VolumeDefinition<I, B, N> definition) {
            throw new AssertionError();
        }
        @Override public <B> EnvironmentId<B> environment(EnvironmentDefinition<B> definition) {
            throw new AssertionError();
        }
    }

    private static final ProgramTicket READY = new ProgramTicket() {
        @Override public State state() { return State.READY; }
        @Override public Optional<ProgramFailure> failure() { return Optional.empty(); }
        @Override public void whenComplete(Consumer<? super Completion> callback) {
            callback.accept(new Ready());
        }
    };
}
