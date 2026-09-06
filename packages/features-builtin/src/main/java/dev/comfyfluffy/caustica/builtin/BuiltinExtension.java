package dev.comfyfluffy.caustica.builtin;

import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.ShaderSource;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.pass.PassRegistration;
import dev.comfyfluffy.caustica.api.session.RenderSessionContribution;
import dev.comfyfluffy.caustica.settings.CausticaSettingsExtension;
import dev.comfyfluffy.caustica.settings.DisplayText;
import dev.comfyfluffy.caustica.settings.FeatureCategory;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;

/** Registers the renderer's diagnostic program implementations and settings. */
public final class BuiltinExtension implements CausticaExtension, CausticaSettingsExtension {
    public static final ResourceId ID = ResourceId.of("caustica", "builtin");

    public interface ImplementationData { }
    public interface BindingData { }
    public interface InstanceData { }
    public interface EnvironmentBindingData { }

    public static final ShaderDataType<ImplementationData> IMPLEMENTATION_DATA =
            ShaderDataType.create("builtin implementation data");
    public static final ShaderDataType<BindingData> BINDING_DATA =
            ShaderDataType.create("builtin binding data");
    public static final ShaderDataType<InstanceData> INSTANCE_DATA =
            ShaderDataType.create("builtin instance data");
    public static final ShaderDataType<EnvironmentBindingData> ENVIRONMENT_BINDING_DATA =
            ShaderDataType.create("builtin environment binding data");

    private static final ShaderSource SHADERS = ShaderSource.classpath(
            BuiltinExtension.class, "/caustica/shaders/builtin", "surface", "sky");

    @Override
    public void register(CausticaApi api) {
        var options = api.options();
        api.sessions().add(context -> {
            ProgramRegistration<Programs> registration = context.program().register(builder -> new Programs(
                    builder.surface(SurfaceDefinition.of(
                            SHADERS.definition("caustica_error_surface", "ErrorSurface"),
                            SHADERS.definition("caustica_error_coverage", "ErrorCoverage"),
                            IMPLEMENTATION_DATA.data(0), BINDING_DATA, INSTANCE_DATA)),
                    builder.environment(new EnvironmentDefinition<>(
                            SHADERS.definition("caustica_builtin_sky", "BuiltinEnvironment"),
                            ENVIRONMENT_BINDING_DATA))));
            try {
                PassRegistration bloom = context.passes().addPostEffectPass(BloomPass.ID, setup -> new BloomPass(setup, context.resources(),
                        () -> options.snapshot().options(ID)));
                return contribution(registration, bloom);
            } catch (RuntimeException | Error failure) {
                registration.close();
                throw failure;
            }
        });
    }

    @Override
    public void registerSettings(SettingsRegistry registry) {
        registry.feature(ID)
                .title(DisplayText.translatable("feature.caustica.builtin"))
                .description(DisplayText.translatable("feature.caustica.builtin.description"))
                .category(FeatureCategory.GENERAL)
                .group(BloomPass.GROUP)
                .options(BloomPass.OPTIONS)
                .register();
    }

    private static RenderSessionContribution contribution(ProgramRegistration<?> registration,
                                                           PassRegistration bloom) {
        return new RenderSessionContribution() {
            @Override
            public void stop() {
                bloom.close();
                registration.close();
            }
        };
    }

    public record Programs(SurfaceId<BindingData, InstanceData> errorSurface,
                           EnvironmentId<EnvironmentBindingData> environment) { }
}
