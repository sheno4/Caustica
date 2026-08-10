package dev.comfyfluffy.caustica.api.provider;

public interface SceneProvider {
    default void update() {
    }

    default void prepareFrame() {
    }

    /** Submit this frame's desired retained meshes and world-space instances. */
    default void submitGeometry(SceneGeometrySink sink) {
    }

    default void invalidate() {
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
