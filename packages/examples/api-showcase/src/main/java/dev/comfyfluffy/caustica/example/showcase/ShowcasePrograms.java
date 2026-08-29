package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.ShaderSource;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeDefinition;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class ShowcasePrograms {
    interface ImplementationData { }
    interface SurfaceBindingData { }
    interface VolumeBindingData { }
    interface InstanceData { }
    interface EnvironmentBindingData { }

    static final ShaderDataType<ImplementationData> IMPLEMENTATION = ShaderDataType.create("showcase implementation");
    static final ShaderDataType<SurfaceBindingData> SURFACE_BINDING = ShaderDataType.create("showcase surface binding");
    static final ShaderDataType<VolumeBindingData> VOLUME_BINDING = ShaderDataType.create("showcase volume binding");
    static final ShaderDataType<InstanceData> INSTANCE = ShaderDataType.create("showcase instance");
    static final ShaderDataType<EnvironmentBindingData> ENVIRONMENT_BINDING = ShaderDataType.create("showcase environment binding");

    private static final ShaderSource SOURCE = ShaderSource.classpath(
            ApiShowcaseExtension.class, "/api_showcase/shaders", "world");

    record Exports(SurfaceId<SurfaceBindingData, InstanceData> opaque,
                   SurfaceId<SurfaceBindingData, InstanceData> cutout,
                   VolumeId<VolumeBindingData, InstanceData> volume,
                   EnvironmentId<EnvironmentBindingData> overworldSky,
                   EnvironmentId<EnvironmentBindingData> netherSky,
                   EnvironmentId<EnvironmentBindingData> endSky) { }

    private final ProgramRegistration<Exports> registration;
    private final AtomicBoolean ready = new AtomicBoolean();

    ShowcasePrograms(ProgramChannel programs, Consumer<String> diagnosticSink) {
        registration = programs.register(builder -> new Exports(
                builder.surface(SurfaceDefinition.opaque(
                        SOURCE.definition("showcase_surface", "api_showcase.OpaqueSurface"),
                        IMPLEMENTATION.data(0L), SURFACE_BINDING, INSTANCE)),
                builder.surface(SurfaceDefinition.of(
                        SOURCE.definition("showcase_surface", "api_showcase.TexturedSurface"),
                        SOURCE.definition("showcase_coverage", "api_showcase.TextureCoverage"),
                        IMPLEMENTATION.data(0L), SURFACE_BINDING, INSTANCE)),
                builder.volume(VolumeDefinition.of(
                        SOURCE.definition("showcase_volume", "api_showcase.AbsorbingVolume"),
                        IMPLEMENTATION.data(0L), VOLUME_BINDING, INSTANCE)),
                builder.environment(new EnvironmentDefinition<>(
                        SOURCE.definition("showcase_environment", "api_showcase.OverworldSky"),
                        ENVIRONMENT_BINDING)),
                builder.environment(new EnvironmentDefinition<>(
                        SOURCE.definition("showcase_environment", "api_showcase.NetherSky"),
                        ENVIRONMENT_BINDING)),
                builder.environment(new EnvironmentDefinition<>(
                        SOURCE.definition("showcase_environment", "api_showcase.EndSky"),
                        ENVIRONMENT_BINDING))));
        registration.whenComplete(completion -> {
            if (completion instanceof ProgramRegistration.Ready) {
                ready.set(true);
            } else if (completion instanceof ProgramRegistration.Failed failed) {
                diagnosticSink.accept(failed.failure().summary() + "\n" + failed.failure().diagnostics());
            }
        });
    }

    Exports exports() {
        return registration.exports();
    }

    boolean ready() {
        return ready.get();
    }

    EnvironmentBinding<EnvironmentBindingData> environmentBinding(
            EnvironmentId<EnvironmentBindingData> implementation, long bindingWord, Runnable retired) {
        return new EnvironmentBinding<>(implementation,
                ENVIRONMENT_BINDING.data(bindingWord), retired);
    }

    void close() {
        registration.close();
    }
}
