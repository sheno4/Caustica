package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.DisplayText;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.Slots;
import dev.comfyfluffy.caustica.minecraft.overlay.WorldOverlayPass;
import dev.comfyfluffy.caustica.minecraft.cloud.MinecraftCloudSceneProvider;
import dev.comfyfluffy.caustica.minecraft.provider.MinecraftLightProvider;
import dev.comfyfluffy.caustica.minecraft.provider.MinecraftMaterialSource;
import dev.comfyfluffy.caustica.minecraft.provider.MinecraftSceneProvider;
import dev.comfyfluffy.caustica.minecraft.sky.SkyLutPass;

/** Installs Minecraft as scene, light, and material input to the host-neutral renderer API. */
public final class MinecraftProvidersExtension implements CausticaExtension {
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft");
    public static final ResourceId END_PORTAL_SURFACE = ResourceId.of("caustica", "end_portal");
    public static final ResourceId WATER_SURFACE = ResourceId.of("caustica", "minecraft_water");

    @Override
    public void register(CausticaRegistry registry) {
        registry.feature(ID)
                .title(DisplayText.literal("Minecraft"))
                .shaderSource(ShaderSource.classpath("/caustica/shaders/minecraft", "surface", "sky"))
                .bind(Slots.SKY, "caustica_minecraft_overworld_sky", "MinecraftOverworldSky")
                .surface(END_PORTAL_SURFACE, "caustica_portal_surface", "PortalSurface")
                .surface(WATER_SURFACE, "caustica_water_surface", "WaterSurface")
                .passResourceModule("caustica_minecraft_sky_bindings")
                .group(SkyLutPass.GROUP)
                .options(SkyLutPass.OPTIONS)
                .renderPass(new SkyLutPass())
                .renderPass(new WorldOverlayPass())
                .sceneProvider(MinecraftSceneProvider.ID, new MinecraftSceneProvider())
                .sceneProvider(MinecraftCloudSceneProvider.ID, new MinecraftCloudSceneProvider())
                .lightProvider(MinecraftLightProvider.ID, new MinecraftLightProvider())
                .materialSource(MinecraftMaterialSource.ID, new MinecraftMaterialSource())
                .register();
    }
}
