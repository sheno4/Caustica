package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.frame.SceneFrame;

/**
 * Session-scoped source of frame-coherent scene data. Retained changes prepared independently use the
 * session's geometry and light channels; only work that must join a particular frame uses this callback.
 */
@FunctionalInterface
public interface SceneProvider {
    /**
     * Snapshots source state and submits data that must land in this frame. CPU preparation belongs on the
     * source's own workers; there is no separate global prepare phase. Use the context's explicit writer
     * rather than timing calls to asynchronous retained channels.
     */
    void submitFrame(SceneFrame frame);
}
