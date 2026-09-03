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
 * <p>This is the renderer's own submission path, not a parallel one offered to extensions: acceleration
 * structure builds and internal transfers are ordinary jobs here and take exactly the guarantees written
 * below. Recorded jobs batch together into shared command buffers and submissions, so a producer must not
 * assume its commands are alone in a batch.
 *
 * <p>A resource consumed later by graphics must be created for every family returned by
 * {@link #sharedQueueFamilyIndices()} when more than one family is present. The recorder must finish with
 * the image layout transitions its eventual consumer requires; queue-family sharing and layout are state
 * the renderer cannot supply on the producer's behalf.
 *
 * <p>Completion-before-publication is the required immutable-resource pattern, and it is the whole of the
 * cross-queue contract: keep the result and its unsealed resource generation private while the job is
 * pending, then seal the generation and publish its retained value only after
 * {@link GpuComputeCompletion.Succeeded}. Failed or cancelled jobs publish nothing and drop the unsealed
 * generation. A successful completion is reported only once the device has retired the job, so graphics
 * work submitted after a callback observes the published value reads it with a complete Vulkan memory
 * dependency and needs no barrier of its own. Graphics never waits on this queue.
 *
 * <p>Republishing means allocating, never overwriting. Rewriting memory a live graphics frame still reads
 * is the opposite hazard, which completion cannot order; a resource must stay unreachable to graphics
 * until its last frame use has retired.
 *
 * <p>Work whose result must appear in the frame that requested it does not belong here. Publication is
 * always at least one {@code progress} boundary behind submission, by design: the trade is a bounded
 * frame of latency instead of a variable graphics stall.
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
