package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.client.VanillaRenderController;
import dev.comfyfluffy.caustica.client.WorldRenderScaler;
import dev.comfyfluffy.caustica.engine.material.MaterialEmissionIndex;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftEmissionSemantics;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialClassifier;
import dev.comfyfluffy.caustica.rt.RtRuntimeHost;

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
    public MaterialEmissionIndex analyzeMaterialEmission() {
        return MinecraftEmissionSemantics.analyze();
    }

    @Override
    public float dielectricIor(ResourceId material) {
        return MinecraftMaterialClassifier.dielectricIor(material);
    }
}
