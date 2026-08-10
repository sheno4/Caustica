package dev.comfyfluffy.caustica.api.provider;

public interface LightProvider {
    default void prepareFrame() {
    }

    /** Contribute a complete snapshot of this provider's lights for the current frame. */
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
