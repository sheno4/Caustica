package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.client.VanillaRenderController;
import dev.comfyfluffy.caustica.minecraft.client.WorldRenderComposite;
import dev.comfyfluffy.caustica.spi.host.RuntimeHost;


/** Minecraft lifecycle and material-policy adapter for the renderer runtime. */
public final class MinecraftRuntimeHost implements RuntimeHost {
    private final VanillaRenderController renderController;
    private final WorldRenderComposite worldComposite;
    private final MinecraftUiOverlay uiOverlay;

    public MinecraftRuntimeHost(VanillaRenderController renderController, WorldRenderComposite worldComposite,
            MinecraftUiOverlay uiOverlay) {
        this.renderController = java.util.Objects.requireNonNull(renderController, "renderController");
        this.worldComposite = java.util.Objects.requireNonNull(worldComposite, "worldComposite");
        this.uiOverlay = java.util.Objects.requireNonNull(uiOverlay, "uiOverlay");
    }

    @Override
    public void resetPresentationFailure() {
        renderController.resetFailureLatch();
    }

    @Override
    public void resetFrameBridge() {
        worldComposite.destroy();
    }

    @Override
    public void destroyUiPresentation() {
        uiOverlay.destroy();
    }

}
