package dev.comfyfluffy.caustica.minecraft.entity;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;

/** Fabric collector adapter for the shared entity capture implementation. */
public final class RtEntityCollector extends RtEntityCollectorBase implements SubmitNodeCollector {
    public OrderedSubmitNodeCollector order(int order) {
        setOrder(order);
        return this;
    }

    @Override
    public <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node) {
    }
}
