package dev.comfyfluffy.caustica.api.scene;

/**
 * Session-scoped source of frame-coherent scene data. Retained changes prepared independently use the
 * session's geometry and light channels; only work that must join a particular frame uses this callback.
 */
public interface SceneProvider {
    /**
     * Snapshots source state and submits data that must land in this frame. CPU preparation belongs on the
     * source's own workers; there is no separate global prepare phase. Use the context's explicit writer
     * rather than timing calls to asynchronous retained channels.
     */
    default void submitFrame(SceneFrameContext frame) {
    }
}
