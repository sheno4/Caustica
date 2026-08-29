package dev.comfyfluffy.caustica.minecraft;

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
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.session.RenderSessionContext;
import dev.comfyfluffy.caustica.builtin.BuiltinExtension;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionFactory;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftProvidersExtensionTest {
    @Test
    void builtinAndMinecraftSettingsUseTheIndependentSettingsRegistry() {
        SettingsRegistry settings = new SettingsRegistry();
        new BuiltinExtension().registerSettings(settings);
        new MinecraftProvidersExtension().registerSettings(settings);

        assertTrue(settings.declared(BuiltinExtension.ID));
        assertTrue(settings.declared(MinecraftProvidersExtension.ID));
    }

    @Test
    void minecraftProgramSetIsOneAtomicRegistrationWithTheAbiModules() {
        List<MinecraftWorldSessionFactory> factories = new ArrayList<>();
        MinecraftApi api = new MinecraftApi(factory -> {
            factories.add(factory);
            return () -> { };
        });
        new MinecraftProvidersExtension().registerMinecraft(api);
        CapturingPrograms programs = new CapturingPrograms();

        List<EnvironmentBinding<?>> environments = new ArrayList<>();
        var contribution = factories.getFirst().open(context(programs, environments));

        assertEquals(1, programs.registrations);
        assertEquals(List.of("caustica_minecraft_surface", "caustica_water_surface",
                        "caustica_portal_surface"),
                programs.surfaces.stream().map(definition -> definition.surface().module()).toList());
        assertEquals(List.of("caustica_minecraft_coverage", "caustica_minecraft_coverage",
                        "caustica_minecraft_coverage"),
                programs.surfaces.stream().map(definition -> definition.coverage().module()).toList());
        assertEquals("caustica_water_surface", programs.volumes.getFirst().implementation().module());
        assertEquals("WaterVolume", programs.volumes.getFirst().implementation().type());
        assertEquals("caustica_minecraft_overworld_sky",
                programs.environments.getFirst().implementation().module());
        assertEquals(0L, programs.surfaces.getFirst().implementationData().bits(),
                "material GPU table address remains the geometry/runtime integration handoff");
        assertTrue(environments.isEmpty(),
                "the environment remains built-in until its LUT and input binding address are live");

        contribution.stop();
        assertTrue(programs.closed);
    }

    private static dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContext context(
            ProgramChannel programs, List<EnvironmentBinding<?>> environments) {
        RenderSessionContext render = new RenderSessionContext() {
            @Override public dev.comfyfluffy.caustica.api.gpu.GpuDevice gpu() { return null; }
            @Override public ProgramChannel program() { return programs; }
            @Override public dev.comfyfluffy.caustica.api.pass.PassChannel passes() { return null; }
            @Override public dev.comfyfluffy.caustica.api.geometry.GeometryChannel geometry() { return null; }
            @Override public dev.comfyfluffy.caustica.api.light.LightChannel lights() { return null; }
        };
        return new dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContext() {
            @Override public RenderSessionContext renderSession() { return render; }
            @Override public dev.comfyfluffy.caustica.api.scene.SceneId scene() {
                return new dev.comfyfluffy.caustica.api.scene.SceneId() { };
            }
            @Override public MinecraftDimensionKey dimension() {
                return MinecraftDimensionKey.of("minecraft", "overworld");
            }
            @Override public ResourcePackEpoch resourcePackEpoch() { return new ResourcePackEpoch(0); }
            @Override public dev.comfyfluffy.caustica.minecraft.api.MinecraftEnvironmentSelector environment() {
                return environments::add;
            }
        };
    }

    private static final class CapturingPrograms implements ProgramChannel, ProgramBuilder {
        private final List<SurfaceDefinition<?, ?, ?>> surfaces = new ArrayList<>();
        private final List<VolumeDefinition<?, ?, ?>> volumes = new ArrayList<>();
        private final List<EnvironmentDefinition<?>> environments = new ArrayList<>();
        private int registrations;
        private boolean closed;

        @Override
        public <E> ProgramRegistration<E> register(
                java.util.function.Function<? super ProgramBuilder, ? extends E> declaration) {
            registrations++;
            E exports = declaration.apply(this);
            return new ProgramRegistration<>() {
                @Override public E exports() { return exports; }
                @Override public ProgramTicket readiness() { return TICKET; }
                @Override public void close() { closed = true; }
            };
        }

        @Override
        public <I, B, N> SurfaceId<B, N> surface(SurfaceDefinition<I, B, N> definition) {
            surfaces.add(definition);
            return new SurfaceId<>() { };
        }

        @Override
        public <I, B, N> VolumeId<B, N> volume(VolumeDefinition<I, B, N> definition) {
            volumes.add(definition);
            return new VolumeId<>() { };
        }

        @Override
        public <B> EnvironmentId<B> environment(EnvironmentDefinition<B> definition) {
            environments.add(definition);
            return new EnvironmentId<>() { };
        }
    }

    private static final ProgramTicket TICKET = new ProgramTicket() {
        @Override public State state() { return State.PENDING; }
        @Override public Optional<ProgramFailure> failure() { return Optional.empty(); }
        @Override public void whenComplete(java.util.function.Consumer<? super Completion> callback) { }
    };
}
