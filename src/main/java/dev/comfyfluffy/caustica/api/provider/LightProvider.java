package dev.comfyfluffy.caustica.api.provider;

import net.minecraft.resources.Identifier;

public interface LightProvider {
    Identifier id();

    default void prepareFrame() {
    }

    /**
     * Contribute this frame's lights to {@code sink}. No-op by default. See {@link LightSink} for what
     * this does and does not do today.
     */
    default void submitLights(LightSink sink) {
    }

    default void onResourceReload() {
    }

    default void shutdown() {
    }
}
