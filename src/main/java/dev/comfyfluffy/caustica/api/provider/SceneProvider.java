package dev.comfyfluffy.caustica.api.provider;

public interface SceneProvider {
    /** Publish the immutable material semantics used by source workers for this resource epoch. */
    default void onMaterialEpoch(MaterialSnapshot materials) {
    }

    /**
     * Stop dispatching work against the closing material epoch. The immutable snapshot remains readable by
     * already-running jobs, but their results no longer belong to the active epoch and must not be submitted.
     */
    default void onMaterialEpochClosing() {
    }

    default void update() {
    }

    default void prepareFrame() {
    }

    /** Submit newly discovered source-local textures before geometry that references them. */
    default void submitTextures(TextureSink sink) {
    }

    /** Submit changed retained-geometry groups produced at the update cadence. */
    default void submitGeometryUpdates(SceneGeometryUpdateContext update) {
    }

    /** Submit changed retained-geometry groups for this frame. */
    default void submitGeometry(SceneFrameContext frame) {
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
