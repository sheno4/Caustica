package dev.comfyfluffy.caustica.api.frame;

/**
 * Explicit destination for changes that must be visible in the frame currently being collected. A writer
 * is valid only during its provider callback, on that callback's thread, and must not be retained.
 */
public interface SceneFrameWriter {
    /**
     * Publishes retained geometry and light operations together at this frame boundary. Validation and
     * callback ownership transfer are synchronous and all-or-nothing. If this method throws, the mutation's
     * callback and resources remain caller-owned; an accepted callback runs exactly once and never inline.
     */
    void submit(SceneMutation mutation);
}
