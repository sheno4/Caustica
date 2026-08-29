package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.client.VanillaRenderController;
import dev.comfyfluffy.caustica.client.WorldRenderScaler;
import dev.comfyfluffy.caustica.spi.host.RuntimeHost;


/** Minecraft lifecycle and material-policy adapter for the renderer runtime. */
public final class MinecraftRuntimeHost implements RuntimeHost {
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

}
