package dev.comfyfluffy.caustica.api.vulkan;

/** Cancellation capability for one accepted compute-queue job. */
public interface GpuComputeJob extends AutoCloseable {
    /**
     * Request cancellation without blocking.
     *
     * <p>Cancellation succeeds only before the job begins recording. The completion callback reports
     * whether cancellation won or already-recording work completed normally. Closing more than once has
     * no additional effect.
     */
    @Override
    void close();
}
