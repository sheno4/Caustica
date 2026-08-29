package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.ShaderDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderSource;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeDefinition;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContribution;
import dev.comfyfluffy.caustica.settings.CausticaSettingsExtension;
import dev.comfyfluffy.caustica.settings.DisplayText;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;

/** Registers Minecraft's world program implementations and settings. */
public final class MinecraftProvidersExtension implements MinecraftExtension, CausticaSettingsExtension {
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft");

    public interface ImplementationData { }
    public interface PrimitiveData { }
    public interface InstanceData { }
    public interface EnvironmentBindingData { }

    public static final ShaderDataType<ImplementationData> IMPLEMENTATION_DATA =
            ShaderDataType.create("Minecraft implementation data");
    public static final ShaderDataType<PrimitiveData> PRIMITIVE_DATA =
            ShaderDataType.create("Minecraft primitive data");
    public static final ShaderDataType<InstanceData> INSTANCE_DATA =
            ShaderDataType.create("Minecraft instance data");
    public static final ShaderDataType<EnvironmentBindingData> ENVIRONMENT_BINDING_DATA =
            ShaderDataType.create("Minecraft environment binding data");

    private static final ShaderSource SHADERS = ShaderSource.classpath(
            MinecraftProvidersExtension.class, "/caustica/shaders/minecraft", "surface", "sky");

    @Override
    public void registerMinecraft(MinecraftApi api) {
        api.sessions().add(context -> {
            ProgramRegistration<Programs> registration = context.renderSession().program().register(builder -> {
                var coverage = shader("caustica_minecraft_coverage", "MinecraftCoverage");
                var implementationData = IMPLEMENTATION_DATA.data(0);
                return new Programs(
                        builder.surface(SurfaceDefinition.of(
                                shader("caustica_minecraft_surface", "MinecraftSurface"), coverage,
                                implementationData, PRIMITIVE_DATA, INSTANCE_DATA)),
                        builder.surface(SurfaceDefinition.of(
                                shader("caustica_water_surface", "WaterSurface"), coverage,
                                implementationData, PRIMITIVE_DATA, INSTANCE_DATA)),
                        builder.surface(SurfaceDefinition.of(
                                shader("caustica_portal_surface", "PortalSurface"), coverage,
                                implementationData, PRIMITIVE_DATA, INSTANCE_DATA)),
                        builder.volume(VolumeDefinition.of(
                                shader("caustica_water_surface", "WaterVolume"), implementationData,
                                PRIMITIVE_DATA, INSTANCE_DATA)),
                        builder.environment(new EnvironmentDefinition<>(
                                shader("caustica_minecraft_overworld_sky", "MinecraftOverworldSky"),
                                ENVIRONMENT_BINDING_DATA)));
            });
            return contribution(registration);
        });
    }

    @Override
    public void registerSettings(SettingsRegistry registry) {
        registry.feature(ID)
                .title(DisplayText.literal("Minecraft"))
                .register();
    }

    private static ShaderDefinition shader(String module, String type) {
        return new ShaderDefinition(SHADERS, module, type);
    }

    private static MinecraftWorldSessionContribution contribution(ProgramRegistration<?> registration) {
        return new MinecraftWorldSessionContribution() {
            @Override public void stop() { registration.close(); }
        };
    }

    public record Programs(SurfaceId<PrimitiveData, InstanceData> materialSurface,
                           SurfaceId<PrimitiveData, InstanceData> waterSurface,
                           SurfaceId<PrimitiveData, InstanceData> portalSurface,
                           VolumeId<PrimitiveData, InstanceData> waterVolume,
                           EnvironmentId<EnvironmentBindingData> environment) { }
}
