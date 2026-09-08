package dev.comfyfluffy.caustica.minecraft.client.entity;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftTelemetry;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;

/** Fabric collector adapter for the shared entity capture implementation. */
public final class RtEntityCollector extends RtEntityCollectorBase {
    public RtEntityCollector(RtEntityTextures textures, MinecraftTelemetry.Instrumentation instrumentation) {
        super(textures, instrumentation);
    }
    @Override
    public <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node) {
    }
}
