package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.client.VanillaRenderController;
import dev.comfyfluffy.caustica.client.WorldRenderScaler;
import dev.comfyfluffy.caustica.spi.host.RuntimeHost;


/** Minecraft lifecycle and material-policy adapter for the renderer runtime. */
public final class MinecraftRuntimeHost implements RuntimeHost {
    private final VanillaRenderController renderController;
    private final WorldRenderScaler renderScaler;
    private final MinecraftUiOverlay uiOverlay;

    public MinecraftRuntimeHost(VanillaRenderController renderController, WorldRenderScaler renderScaler,
            MinecraftUiOverlay uiOverlay) {
        this.renderController = java.util.Objects.requireNonNull(renderController, "renderController");
        this.renderScaler = java.util.Objects.requireNonNull(renderScaler, "renderScaler");
        this.uiOverlay = java.util.Objects.requireNonNull(uiOverlay, "uiOverlay");
    }

    @Override
    public void resetPresentationFailure() {
        renderController.resetFailureLatch();
    }

    @Override
    public void resetFrameBridge() {
        renderScaler.destroy();
    }

    @Override
    public void destroyUiPresentation() {
        uiOverlay.destroy();
    }

}
