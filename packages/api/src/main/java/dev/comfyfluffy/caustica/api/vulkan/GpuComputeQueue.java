package dev.comfyfluffy.caustica.api.vulkan;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.util.List;
import java.util.function.Consumer;

/**
 * Asynchronous Vulkan compute and transfer execution. The platform owns command buffers and submission.
 * Recorders run on its compute worker and must not retain their borrowed command buffer. Each job records
 * into independent command state. Completion reports GPU completion before a result may be published.
 * Graphics submissions importing completed results receive a device memory dependency on their compute
 * writes without waiting for unfinished replacement work.
 *
 * <p>Inputs and outputs must remain alive until completion. Published revisions stay unchanged while
 * retained readers use them. Buffers shared with graphics require concurrent sharing across the families
 * returned by {@link #sharedQueueFamilyIndices()}, or explicit ownership transfers. Producers record image
 * layout transitions required by their consumers.
 *
 * <p>Calls are thread-safe. Completion runs exactly once for each accepted job, never inline. Callbacks
 * must return promptly and must not throw. Work required by the current frame belongs in its ordered
 * graphics recording rather than background preparation.
 */
public interface GpuComputeQueue {
    /** Throwing means no acceptance: the caller still owns both callbacks and their captured resources. */
    GpuComputeJob submit(Consumer<? super VkCommandBuffer> recorder,
                         Consumer<? super GpuComputeCompletion> completion);

    /**
     * Transfer owning references through completion. Rejected submissions leave them with the caller.
     * Accepted references close after the completion callback, including failure and cancellation.
     */
    default GpuComputeJob submit(Consumer<? super VkCommandBuffer> recorder,
                                List<? extends AutoCloseable> dependencies,
                                Consumer<? super GpuComputeCompletion> completion) {
        List<? extends AutoCloseable> owned = List.copyOf(dependencies);
        return submit(recorder, result -> {
            try { completion.accept(result); }
            finally { release(owned); }
        });
    }

    private static void release(List<? extends AutoCloseable> owners) {
        Throwable failure = null;
        for (AutoCloseable owner : owners) {
            try { owner.close(); }
            catch (Throwable error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure != null) throw new IllegalStateException("GPU dependency release failed", failure);
    }

    /** Distinct graphics and compute queue families; one element permits exclusive buffer sharing. */
    int[] sharedQueueFamilyIndices();
}
