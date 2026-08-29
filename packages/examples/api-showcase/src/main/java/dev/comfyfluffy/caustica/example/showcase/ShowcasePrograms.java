package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.program.ProgramTicket;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.ShaderDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderSource;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeDefinition;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;

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
                   EnvironmentId<EnvironmentBindingData> environment) { }

    private final ProgramRegistration<Exports> registration;

    ShowcasePrograms(ProgramChannel programs, Consumer<String> diagnosticSink) {
        registration = programs.register(builder -> new Exports(
                builder.surface(SurfaceDefinition.opaque(
                        shader("showcase_surface", "api_showcase.OpaqueSurface"),
                        IMPLEMENTATION.data(0L), SURFACE_BINDING, INSTANCE)),
                builder.surface(SurfaceDefinition.of(
                        shader("showcase_surface", "api_showcase.TexturedSurface"),
                        shader("showcase_coverage", "api_showcase.TextureCoverage"),
                        IMPLEMENTATION.data(0L), SURFACE_BINDING, INSTANCE)),
                builder.volume(VolumeDefinition.of(
                        shader("showcase_volume", "api_showcase.AbsorbingVolume"),
                        IMPLEMENTATION.data(0L), VOLUME_BINDING, INSTANCE)),
                builder.environment(new EnvironmentDefinition<>(
                        shader("showcase_environment", "api_showcase.GradientEnvironment"),
                        ENVIRONMENT_BINDING))));
        registration.readiness().whenComplete(completion -> {
            if (completion instanceof ProgramTicket.Failed failed) {
                diagnosticSink.accept(failed.failure().summary() + "\n" + failed.failure().diagnostics());
            }
        });
    }

    Exports exports() {
        return registration.exports();
    }

    EnvironmentBinding<EnvironmentBindingData> environmentBinding(long bindingWord, Runnable retired) {
        return new EnvironmentBinding<>(exports().environment(),
                ENVIRONMENT_BINDING.data(bindingWord), retired);
    }

    void close() {
        registration.close();
    }

    private static ShaderDefinition shader(String module, String type) {
        return new ShaderDefinition(SOURCE, module, type);
    }
}
