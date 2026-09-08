package dev.comfyfluffy.caustica.minecraft.client.entity;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftTelemetry;

/** NeoForge collector adapter for the shared entity capture implementation. */
public final class RtEntityCollector extends RtEntityCollectorBase {
    public RtEntityCollector(RtEntityTextures textures, MinecraftTelemetry.Instrumentation instrumentation) {
        super(textures, instrumentation);
    }
}
