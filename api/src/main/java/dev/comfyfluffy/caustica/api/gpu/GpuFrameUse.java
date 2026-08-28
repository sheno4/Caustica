package dev.comfyfluffy.caustica.api.gpu;

/**
 * Completion reservation for GPU resources referenced by the current frame.
 *
 * <p>The renderer owns the underlying synchronization. This type is deliberately callback-only: waiting
 * on the reservation supplied while its frame is still being recorded would prevent that frame from ever
 * being submitted. The reservation is borrowed with its {@code PassFrame}; register callbacks during the
 * pass callback and do not retain it.
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
