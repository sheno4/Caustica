package dev.comfyfluffy.caustica.minecraft.client.entity;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftTelemetry;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;

/** NeoForge collector adapter for the shared entity capture implementation. */
public final class RtEntityCollector extends RtEntityCollectorBase implements SubmitNodeCollector {
    public RtEntityCollector(RtEntityTextures textures, MinecraftTelemetry.Instrumentation instrumentation) {
        super(textures, instrumentation);
    }
    public OrderedSubmitNodeCollector order(int order) {
        setOrder(order);
        return this;
    }
}
