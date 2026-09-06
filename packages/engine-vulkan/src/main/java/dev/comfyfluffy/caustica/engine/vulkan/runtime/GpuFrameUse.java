package dev.comfyfluffy.caustica.engine.vulkan.runtime;


/**
 * Completion reservation for GPU resources referenced by the current frame.
 *
 * <p>The renderer owns the underlying synchronization. Register callbacks during command recording; do not
 * block on or retain the reservation because its frame has not been submitted yet.
 */
public interface GpuFrameUse {
    /**
     * Run after this frame's command buffer has been ended and accepted for deferred graphics execution.
     * The callback runs once on the renderer thread before the frame timeline signal is appended. It never
     * runs when recording is abandoned before command acceptance. Callbacks must not block or throw.
     */
    void whenSubmitted(Runnable callback);

    /**
     * Run a callback off the renderer thread after this frame reservation completes. Use it to retire
     * resources referenced by this frame.
     * Eligible callbacks run in registration order and must not block or throw. Later frames and unrelated
     * GPU work may still be executing. An accepted callback runs exactly once and never inline. If recording
     * is abandoned before submission, it runs once the renderer has established that the abandoned commands
     * cannot execute.
     */
    void whenComplete(Runnable callback);
}
