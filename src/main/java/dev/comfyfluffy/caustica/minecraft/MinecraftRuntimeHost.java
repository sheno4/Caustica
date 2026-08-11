package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.provider.MaterialRule;
import dev.comfyfluffy.caustica.client.VanillaRenderController;
import dev.comfyfluffy.caustica.client.WorldRenderScaler;
import dev.comfyfluffy.caustica.engine.material.MaterialCatalog;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialCatalogBuilder;
import dev.comfyfluffy.caustica.rt.RtRuntimeHost;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;

import java.util.List;

/** Minecraft lifecycle and material-policy adapter for the host-neutral renderer runtime. */
public final class MinecraftRuntimeHost implements RtRuntimeHost {
    public static final MinecraftRuntimeHost INSTANCE = new MinecraftRuntimeHost();

    private MinecraftRuntimeHost() {
    }

    @Override
    public void resetPresentationFailure() {
        VanillaRenderController.INSTANCE.resetFailureLatch();
    }

    @Override
    public void resetFrameBridge() {
        WorldRenderScaler.INSTANCE.destroy();
    }

    @Override
    public void destroyUiPresentation() {
        MinecraftUiOverlay.destroy();
    }

    @Override
    public void resetSceneTextures() {
        RtEntityTextures.INSTANCE.reset();
    }

    @Override
    public MaterialCatalog materialCatalog(List<MaterialRule> rules) {
        return MinecraftMaterialCatalogBuilder.build(rules);
    }
}
