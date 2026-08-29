package dev.comfyfluffy.caustica.api.gpu;

/**
 * Completion reservation for GPU resources referenced by the current frame.
 *
 * <p>The renderer owns the underlying synchronization. Register callbacks during the pass callback; do not
 * block on or retain the reservation because its frame has not been submitted yet.
 */
public interface GpuFrameUse {
    /**
     * Run cleanup on the renderer thread after this frame reservation completes. Eligible callbacks run
     * in registration order and must not block or throw. Later frames and unrelated GPU work may still be
     * executing. An accepted callback runs exactly once and never inline. If recording is abandoned before
     * submission, it runs once the renderer has established that the abandoned commands cannot execute.
     */
    void retire(Runnable cleanup);
}
