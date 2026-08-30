package dev.comfyfluffy.caustica.minecraft.client.entity;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftTelemetry;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;

/** Fabric collector adapter for the shared entity capture implementation. */
public final class RtEntityCollector extends RtEntityCollectorBase implements SubmitNodeCollector {
    public RtEntityCollector(RtEntityTextures textures, MinecraftTelemetry.Instrumentation instrumentation) {
        super(textures, instrumentation);
    }
    public OrderedSubmitNodeCollector order(int order) {
        setOrder(order);
        return this;
    }

    @Override
    public <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node) {
    }
}
