package dev.comfyfluffy.caustica.api.scene;

import java.util.List;

/**
 * Explicit destination for changes that must be visible in the frame currently being collected. A writer
 * is valid only during its provider callback, on that callback's thread, and must not be retained.
 */
public interface SceneFrameWriter {
    /** Publishes retained geometry and light operations together at this frame boundary. */
    void submit(SceneMutation mutation);

    /**
     * Adds frame-only geometry to the root scene. Each batch retirement callback runs after the frame has
     * completed and its mesh inputs are no longer read.
     */
    void submitTransient(List<AtomicBatch<TransientGeometry>> batches);
}
