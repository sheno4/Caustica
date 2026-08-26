package dev.comfyfluffy.caustica.api.gpu;

/**
 * Completion reservation for GPU resources referenced by the current frame.
 *
 * <p>The renderer owns the underlying synchronization. This type is deliberately callback-only: waiting
 * on the reservation supplied while its frame is still being recorded would prevent that frame from ever
 * being submitted.
 */
public interface GpuFrameUse {
    /** Run cleanup after this frame reservation completes without blocking the render thread. */
    void retire(Runnable cleanup);
}
