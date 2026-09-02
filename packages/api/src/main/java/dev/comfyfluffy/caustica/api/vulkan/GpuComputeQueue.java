package dev.comfyfluffy.caustica.api.vulkan;

import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.function.Consumer;

/**
 * Contribution-scoped asynchronous Vulkan compute and transfer recording.
 *
 * <p>The renderer owns command buffers, queue submission, and completion synchronization. A producer
 * allocates its private destination and input resources before submission, then records only the commands
 * which initialize them. The recorder runs once on the renderer's compute executor thread and must not
 * retain the borrowed command buffer.
 *
 * <p>A resource consumed later by graphics must be created for every family returned by
 * {@link #sharedQueueFamilyIndices()} when more than one family is present. The recorder must finish with
 * the Vulkan access and layout transitions required by its eventual consumer. The renderer orders a
 * successful job before later graphics submissions; that does not replace queue-family sharing or the
 * producer's Vulkan memory and layout barriers.
 *
 * <p>Completion-before-publication is the intended immutable-resource pattern: keep the result and its
 * unsealed resource generation private while the job is pending, then seal the generation and publish its
 * retained value only after {@link GpuComputeCompletion.Succeeded}. Failed or cancelled jobs publish
 * nothing and drop the unsealed generation.
 *
 * <p>All methods are thread-safe. Completion callbacks from one render session are serialized through
 * render-session progress, are never invoked inline by {@link #submit}, and return before their
 * contribution's final close callback. A completion callback must return promptly and must not throw.
 */
public interface GpuComputeQueue {
    /**
     * Record and submit one asynchronous job.
     *
     * <p>Acceptance synchronously transfers the recorder and completion callback to the render session.
     * The completion callback runs exactly once for every accepted job. Throwing from this method means
     * the job was not accepted and the caller retains both callbacks and every resource they close over.
     */
    GpuComputeJob submit(Consumer<? super VkCommandBuffer> recorder,
                         Consumer<? super GpuComputeCompletion> completion);

    /**
     * Distinct queue-family indices used by graphics and this compute queue.
     *
     * <p>One element means exclusive sharing is sufficient. More than one means a resource used by both
     * queues needs concurrent sharing or an explicit ownership transfer.
     */
    int[] sharedQueueFamilyIndices();
}
