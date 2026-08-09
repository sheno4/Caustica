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

    /**
     * Stop producing work for this RT session. This runs before GPU queues are drained; implementations
     * must not destroy resources that may still be referenced by submitted work.
     */
    default void stop() {
    }

    /** Release this RT session's state after its GPU work is idle. */
    default void shutdown() {
    }
}
