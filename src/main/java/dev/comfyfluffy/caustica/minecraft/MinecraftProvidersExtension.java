package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.DisplayText;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.builtin.overlay.WorldOverlayPass;
import dev.comfyfluffy.caustica.rt.provider.MinecraftLightProvider;
import dev.comfyfluffy.caustica.rt.provider.MinecraftMaterialSource;
import dev.comfyfluffy.caustica.rt.provider.MinecraftSceneProvider;

/** Installs Minecraft as scene, light, and material input to the host-neutral renderer API. */
public final class MinecraftProvidersExtension implements CausticaExtension {
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft");

    @Override
    public void register(CausticaRegistry registry) {
        registry.feature(ID)
                .title(DisplayText.literal("Minecraft"))
                .renderPass(new WorldOverlayPass())
                .sceneProvider(MinecraftSceneProvider.ID, new MinecraftSceneProvider())
                .lightProvider(MinecraftLightProvider.ID, new MinecraftLightProvider())
                .materialSource(MinecraftMaterialSource.ID, new MinecraftMaterialSource())
                .register();
    }
}
