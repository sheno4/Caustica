package dev.comfyfluffy.caustica.api.gpu;

/**
 * Completion reservation for GPU resources referenced by the current frame.
 *
 * <p>The renderer owns the underlying synchronization. Passes may wait before rewriting a reusable
 * descriptor slot, or defer cleanup until every command recorded for this frame has completed.</p>
 */
public interface GpuFrameUse {
    /**
     * Wait until commands associated with this reservation have completed.
     *
     * <p>Only call this when reusing a resource last referenced by an older frame. Awaiting the token
     * supplied to the frame currently being recorded would wait for work that cannot yet be submitted.</p>
     */
    void awaitCompletion();

    /** Run cleanup after this frame reservation completes without blocking the render thread. */
    void retire(Runnable cleanup);
}
