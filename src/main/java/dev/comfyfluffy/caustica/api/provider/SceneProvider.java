package dev.comfyfluffy.caustica.api.provider;

public interface SceneProvider {
    default void update() {
    }

    default void prepareFrame() {
    }

    /** Submit this frame's desired retained meshes and world-space instances. */
    default void submitGeometry(SceneGeometrySink sink) {
    }

    /** Called whenever this render session enters or leaves a world epoch. */
    default void onWorldChanged() {
    }

    /** Called while the current resource pack is being detached. */
    default void onResourcePackClosing() {
    }

    /** Called after a replacement resource pack becomes active. */
    default void onResourcePackApplied() {
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
