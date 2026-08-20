package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.DisplayText;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.RuntimeActivation;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.Slots;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.minecraft.overlay.WorldOverlayPass;
import dev.comfyfluffy.caustica.minecraft.cloud.MinecraftCloudSceneProvider;
import dev.comfyfluffy.caustica.minecraft.damage.MinecraftDamageModifierPass;
import dev.comfyfluffy.caustica.minecraft.provider.MinecraftLightProvider;
import dev.comfyfluffy.caustica.minecraft.provider.MinecraftMaterialSource;
import dev.comfyfluffy.caustica.minecraft.provider.MinecraftSceneProvider;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialEmissionState;
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
                .shaderSource(ShaderSource.classpath(
                        "/caustica/shaders/minecraft", "surface", "sky", "modifier"))
                .runtimeActivation(RuntimeActivation.ALWAYS)
                .bind(Slots.SKY, "caustica_minecraft_overworld_sky", "MinecraftOverworldSky")
                .surface(END_PORTAL_SURFACE, "caustica_portal_surface", "PortalSurface")
                .surface(WATER_SURFACE, "caustica_water_surface", "WaterSurface")
                .passResourceModule("caustica_minecraft_sky_bindings")
                .surfaceModifier(MinecraftDamageModifierPass.MODIFIER_ID,
                        "caustica_minecraft_damage_modifier", "MinecraftDamageModifier")
                .passResourceModule("caustica_minecraft_damage_bindings")
                .group(SkyLutPass.GROUP)
                .options(SkyLutPass.OPTIONS)
                .renderPass(SkyLutPass.ID, RenderStage.ENVIRONMENT_PREPARE, SkyLutPass::new)
                .renderPass(MinecraftDamageModifierPass.ID, RenderStage.BEFORE_TRACE,
                        MinecraftDamageModifierPass::new)
                .renderPass(WorldOverlayPass.ID, RenderStage.OVERLAY, WorldOverlayPass::new)
                .sceneProviderContextual(MinecraftSceneProvider.ID, context -> new MinecraftSceneProvider(
                        context.getOrCreate(MinecraftMaterialEmissionState.CONTEXT_KEY,
                                MinecraftMaterialEmissionState::new)))
                .sceneProvider(MinecraftCloudSceneProvider.ID, MinecraftCloudSceneProvider::new)
                .lightProvider(MinecraftLightProvider.ID, MinecraftLightProvider::new)
                .materialSourceContextual(MinecraftMaterialSource.ID, context -> new MinecraftMaterialSource(
                        context.getOrCreate(MinecraftMaterialEmissionState.CONTEXT_KEY,
                                MinecraftMaterialEmissionState::new)))
                .register();
    }
}
