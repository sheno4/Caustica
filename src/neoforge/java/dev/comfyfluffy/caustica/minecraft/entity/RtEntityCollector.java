package dev.comfyfluffy.caustica.minecraft.entity;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;

/** NeoForge collector adapter for the shared entity capture implementation. */
public final class RtEntityCollector extends RtEntityCollectorBase implements SubmitNodeCollector {
    public RtEntityCollector(RtEntityTextures textures) { super(textures); }
    public OrderedSubmitNodeCollector order(int order) {
        setOrder(order);
        return this;
    }
}
