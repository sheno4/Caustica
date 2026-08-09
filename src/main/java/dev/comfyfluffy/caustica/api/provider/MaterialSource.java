package dev.comfyfluffy.caustica.api.provider;

import net.minecraft.resources.Identifier;

public interface MaterialSource {
    Identifier id();

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
