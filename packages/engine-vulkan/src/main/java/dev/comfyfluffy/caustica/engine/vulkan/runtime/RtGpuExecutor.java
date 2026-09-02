package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeJob;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.engine.vulkan.VulkanDiagnostics;

import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanQueueRef;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

/**
 * Single-owner asynchronous GPU submission lane on a queue reserved by Caustica at device creation.
 * The host never fetches or submits to this reserved queue, so the executor exclusively satisfies Vulkan's
 * queue-synchronization rule while sharing the logical device with graphics work.
 */
public final class RtGpuExecutor implements GpuComputeQueue {
    private static final int MAX_BUILD_BATCH = 128;
    private static final Job STOP = new Job(null, null, null, null, null);
    private static final long BUILD_READ_STAGES = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

    private final VulkanDeviceContext ctx;
    private final VulkanQueueRef computeQueue;
    private final long buildTimeline;
    private final long graphicsTimeline;
    private final LinkedBlockingQueue<Job> jobs = new LinkedBlockingQueue<>();
    private final AtomicLong nextBuildValue = new AtomicLong();
    private final AtomicLong pendingPublishWaitValue = new AtomicLong();
    private final AtomicLong nextGraphicsValue = new AtomicLong();
    private final AtomicLong latestSubmittedGraphicsValue = new AtomicLong();
    private final ArrayList<DestroyJob> destroyJobs = new ArrayList<>();
    private final Object submissionLock = new Object();
    private long submittedBuildValue;
    private long completedBuildValue;
    private final Thread thread;
    private long commandPool;
    private volatile boolean closed;
    private volatile Throwable executorFailure;

    RtGpuExecutor(VulkanDeviceContext ctx) {
        this.ctx = ctx;
        this.computeQueue = ctx.computeQueue();
        this.buildTimeline = createTimeline("RT build timeline");
        this.graphicsTimeline = createTimeline("RT graphics-use timeline");
        createCommandPool();
        this.thread = new Thread(this::run, "Caustica GPU executor");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    @Override
    public GpuComputeJob submit(Consumer<? super VkCommandBuffer> recorder,
                                Consumer<? super GpuComputeCompletion> completion) {
        java.util.Objects.requireNonNull(recorder, "recorder");
        java.util.Objects.requireNonNull(completion, "completion");
        AtomicBoolean cancelled = new AtomicBoolean();
        submit(cancelled::get, recorder::accept, () -> { }, (build, failure) -> {
            if (failure == null) {
                // The first later graphics submission waits on this signal even though host completion
                // was observed, establishing the cross-queue Vulkan memory dependency for published output.
                pendingPublishWaitValue.accumulateAndGet(build.value, Math::max);
            }
            GpuComputeCompletion result = failure == null
                    ? new GpuComputeCompletion.Succeeded()
                    : failure instanceof CancellationException
                    ? new GpuComputeCompletion.Cancelled()
                    : new GpuComputeCompletion.Failed(failure);
            completion.accept(result);
        });
        return () -> cancelled.set(true);
    }

    @Override
    public int[] sharedQueueFamilyIndices() {
        return ctx.asyncBufferSharingQueueFamilies();
    }

    /**
     * Enqueue GPU recording. On success, {@code afterSuccess} runs after timeline completion; then
     * {@code finished} receives exactly one terminal success/failure notification on this thread.
     */
    public Build submit(Consumer<VkCommandBuffer> record, Runnable afterSuccess,
                        BiConsumer<Build, Throwable> finished) {
        return submit(() -> false, record, afterSuccess, finished);
    }

    /**
     * Enqueue cancellable GPU recording. Cancellation is sampled immediately before a queued job enters
     * a command-buffer batch; already-recording or submitted work still completes normally.
     */
    public synchronized Build submit(BooleanSupplier cancelled, Consumer<VkCommandBuffer> record,
                                     Runnable afterSuccess, BiConsumer<Build, Throwable> finished) {
        checkExecutorFailure();
        if (closed) {
            throw new IllegalStateException("RT GPU executor is closed");
        }
        long value = nextBuildValue.incrementAndGet();
        Build build = new Build(value);
        jobs.add(new Job(cancelled, record, afterSuccess, finished, build));
        return build;
    }

    /** Mark a build visible to publication; the next graphics frame use waits on it. */
    public void markPublished(Build build) {
        assertRenderThread();
        pendingPublishWaitValue.accumulateAndGet(build.value, Math::max);
    }

    /** Attach published-build waits and reserve the completion token shared by this frame's RT resources. */
    public GraphicsUse beginGraphicsUse(GraphicsSubmission submission) {
        assertRenderThread();
        checkExecutorFailure();
        // Host operations on graphicsTimeline are render-thread-affine so its query is ordered with the
        // host graphics submission that signals it.
        processDestroyJobs();
        long waitValue = pendingPublishWaitValue.get();
        if (waitValue != 0L) {
            // vkQueuePresentKHR requires every transitive signal dependency of its binary wait to have
            // already been submitted. A Build is assigned its timeline value when queued on this Java
            // executor, so do not expose that future value to the graphics/present chain prematurely.
            awaitBuildSubmission(waitValue);
            enqueueBuildWait(submission, buildTimeline, waitValue);
        }
        return new GraphicsUse(this, nextGraphicsValue.incrementAndGet());
    }

    /**
     * Resolve one frame reservation and signal it only when its commands entered the host submission.
     * Callback publication and the timeline signal are one terminal operation: either may fail, but the
     * reservation cannot remain open or be resolved a second time.
     */
    public void resolveGraphicsUse(GraphicsSubmission submission, GraphicsUse graphicsUse) {
        assertRenderThread();
        if (graphicsUse.owner() != this) {
            throw new IllegalArgumentException("Graphics use belongs to a different Vulkan device");
        }
        graphicsUse.resolveSubmission(() -> {
            enqueueGraphicsSignal(submission, graphicsTimeline, graphicsUse.value());
            latestSubmittedGraphicsValue.accumulateAndGet(graphicsUse.value(), Math::max);
        });
    }

    static void enqueueBuildWait(GraphicsSubmission submission, long semaphore, long value) {
        submission.waitSemaphore(semaphore, value, BUILD_READ_STAGES);
    }

    static void enqueueGraphicsSignal(GraphicsSubmission submission, long semaphore, long value) {
        submission.signalSemaphore(semaphore, value, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
    }

    /** Create a waiter that shares one completed-value snapshot across several resource reuse checks. */
    public GraphicsUseWaiter graphicsUseWaiter() {
        assertRenderThread();
        checkExecutorFailure();
        return new GraphicsUseWaiter(queryTimeline(graphicsTimeline));
    }

    /** Rethrow a latched executor failure on the calling thread. */
    public void throwIfFailed() {
        checkExecutorFailure();
    }

    /** Destroy an owner once the specified submitted frame has completed. */
    public void retireAfterGraphics(GraphicsUse lastUse, Runnable destroy) {
        enqueueDestroyAfterGraphicsValue(lastUse.value(), destroy);
    }

    /** Destroy a tracked owner once its exact last frame use has completed. */
    public void retireAfterGraphics(TrackedGraphicsUse trackedUse, Runnable destroy) {
        assertRenderThread();
        trackedUse.whenMarksApplied(() -> enqueueDestroyAfterGraphicsValue(trackedUse.value, destroy));
    }

    /** Retire extension-owned state after the latest graphics submission accepted before this call. */
    public void retireAfterLatestSubmittedGraphics(Runnable destroy) {
        enqueueDestroyAfterGraphicsValue(latestSubmittedGraphicsValue.get(), destroy);
    }

    private void enqueueDestroyAfterGraphicsValue(long lastUseValue, Runnable destroy) {
        checkExecutorFailure();
        synchronized (destroyJobs) {
            destroyJobs.add(new DestroyJob(lastUseValue, destroy));
        }
    }

    void keepAliveAfterGraphicsValue(long lastUseValue, Runnable release) {
        synchronized (destroyJobs) {
            destroyJobs.add(new DestroyJob(lastUseValue, release));
        }
    }

    public boolean hasPendingDestroys() {
        synchronized (destroyJobs) {
            return !destroyJobs.isEmpty();
        }
    }

    /** Caller has made the device idle; all queued destruction is now unconditionally safe. */
    public void flushDestroysAfterDeviceIdle() {
        Throwable failure = executorFailure;
        synchronized (destroyJobs) {
            for (DestroyJob job : destroyJobs) {
                try {
                    job.destroy.run();
                } catch (Throwable t) {
                    if (failure == null) {
                        failure = t;
                    } else {
                        failure.addSuppressed(t);
                    }
                }
            }
            destroyJobs.clear();
        }
        if (failure != null) {
            throw new IllegalStateException("RT GPU executor failed", failure);
        }
    }

    /**
     * Drain every job accepted before this call, then wait all device queues idle without closing this
     * per-device executor. Session producers must already be stopped, so no later job can race the idle
     * boundary before session-owned resources are destroyed.
     */
    public void drainAndWaitIdle() {
        long target = nextBuildValue.get();
        synchronized (submissionLock) {
            while (completedBuildValue < target && executorFailure == null) {
                try {
                    submissionLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while draining RT GPU executor", e);
                }
            }
        }
        checkExecutorFailure();
        ctx.waitIdle();
        flushDestroysAfterDeviceIdle();
    }

    public synchronized void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        jobs.add(STOP);
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping RT GPU executor", e);
        }
        // Stop and join first: waiting idle before the executor stops leaves a race where it can
        // submit immediately after vkDeviceWaitIdle returns. The idle wait also makes graphics-side
        // timeline semaphore use complete before those semaphores are destroyed below.
        ctx.waitIdle();
        Throwable failure = null;
        try {
            flushDestroysAfterDeviceIdle();
        } catch (Throwable t) {
            failure = t;
        }
        VK10.vkDestroyCommandPool(ctx.vk(), commandPool, null);
        commandPool = 0L;
        VK10.vkDestroySemaphore(ctx.vk(), graphicsTimeline, null);
        VK10.vkDestroySemaphore(ctx.vk(), buildTimeline, null);
        if (failure != null) {
            throw new IllegalStateException("RT GPU executor shutdown failed", failure);
        }
    }

    private void run() {
        while (true) {
            Job first;
            try {
                first = jobs.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failQueuedJobs(e);
                return;
            }
            if (first == STOP) {
                return;
            }
            ArrayList<Job> batch = new ArrayList<>(MAX_BUILD_BATCH);
            batch.add(first);
            boolean stopAfterBatch = false;
            while (batch.size() < MAX_BUILD_BATCH) {
                Job next = jobs.poll();
                if (next == null) {
                    break;
                }
                if (next == STOP) {
                    stopAfterBatch = true;
                    break;
                }
                batch.add(next);
            }

            ArrayList<Job> executable = new ArrayList<>(batch.size());
            for (Job job : batch) {
                if (job.cancelled.getAsBoolean()) {
                    finishJob(job, new CancellationException("RT GPU job epoch is stale"));
                } else {
                    executable.add(job);
                }
            }
            if (!executable.isEmpty()) {
                try {
                    execute(executable);
                    for (Job job : executable) {
                        finishJob(job, null);
                    }
                } catch (Throwable t) {
                    latchFailure(t);
                    for (Job job : executable) {
                        finishJob(job, t);
                    }
                }
            }
            if (executorFailure != null) {
                failQueuedJobs(executorFailure);
                return;
            }
            if (stopAfterBatch) {
                return;
            }
        }
    }

    private void finishJob(Job job, Throwable failure) {
        if (failure == null) {
            try {
                job.afterSuccess.run();
            } catch (Throwable t) {
                failure = t;
            }
        }
        try {
            job.finished.accept(job.build, failure);
        } catch (Throwable t) {
            if (failure != null) {
                failure.addSuppressed(t);
            }
            latchFailure(t);
        } finally {
            synchronized (submissionLock) {
                completedBuildValue = Math.max(completedBuildValue, job.build.value);
                submissionLock.notifyAll();
            }
        }
    }

    /** Fail every accepted build before the executor thread exits so task ownership always unwinds. */
    private synchronized void failQueuedJobs(Throwable failure) {
        Throwable terminal = failure != null ? failure : new IllegalStateException("RT GPU executor stopped");
        latchFailure(terminal);
        Job job;
        while ((job = jobs.poll()) != null) {
            if (job != STOP) {
                finishJob(job, terminal);
            }
        }
    }

    private synchronized void latchFailure(Throwable failure) {
        if (executorFailure == null) {
            executorFailure = failure;
        } else if (executorFailure != failure) {
            executorFailure.addSuppressed(failure);
        }
        synchronized (submissionLock) {
            submissionLock.notifyAll();
        }
    }

    private void checkExecutorFailure() {
        Throwable failure = executorFailure;
        if (failure != null) {
            throw new IllegalStateException("RT GPU executor failed", failure);
        }
    }

    private void assertRenderThread() {
        ctx.backend().assertRenderThread();
    }

    private void processDestroyJobs() {
        assertRenderThread();
        if (!hasPendingDestroys()) {
            return;
        }
        long completed = queryTimeline(graphicsTimeline);
        synchronized (destroyJobs) {
            Iterator<DestroyJob> it = destroyJobs.iterator();
            while (it.hasNext()) {
                DestroyJob job = it.next();
                if (job.lastUseValue <= completed) {
                    it.remove();
                    job.destroy.run();
                }
            }
        }
    }

    private void execute(List<Job> batch) {
        VkCommandBuffer cmd = null;
        boolean submitted = false;
        boolean completed = false;
        long signalValue = batch.get(batch.size() - 1).build.value;
        long firstValue = batch.get(0).build.value;
        VulkanDiagnostics.setInFlight("async-compute",
                "recording builds=" + firstValue + ".." + signalValue + " batch=" + batch.size()
                        + " queued=" + jobs.size());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            ctx.checkDeviceResult(VK10.vkAllocateCommandBuffers(ctx.vk(), ai, pCmd), "vkAllocateCommandBuffers(RT GPU executor)");
            cmd = new VkCommandBuffer(pCmd.get(0), ctx.vk());
            VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            ctx.checkDeviceResult(VK10.vkBeginCommandBuffer(cmd, bi), "vkBeginCommandBuffer(RT GPU executor)");
            for (Job job : batch) {
                job.record.accept(cmd);
            }
            ctx.checkDeviceResult(VK10.vkEndCommandBuffer(cmd), "vkEndCommandBuffer(RT GPU executor)");
            VkCommandBufferSubmitInfo.Buffer command = VkCommandBufferSubmitInfo.calloc(1, stack)
                    .sType$Default().commandBuffer(cmd);
            VkSemaphoreSubmitInfo.Buffer signal = VkSemaphoreSubmitInfo.calloc(1, stack)
                    .sType$Default().semaphore(buildTimeline).value(signalValue)
                    // Jobs also contain pure transfer uploads (for example the device-local light
                    // proposal tables). Signal only after every command in the batch, not merely the
                    // AS-build stage, so a graphics wait cannot overtake such a copy.
                    .stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            VkSubmitInfo2.Buffer submit = VkSubmitInfo2.calloc(1, stack).sType$Default()
                    .pCommandBufferInfos(command).pSignalSemaphoreInfos(signal);
            VulkanDiagnostics.noteQueueSubmission(computeQueue.queue(), "Caustica compute queue");
            synchronized (ctx.deviceQueueHostLock()) {
                ctx.checkDeviceResult(VK13.vkQueueSubmit2(
                        computeQueue.queue(), submit, 0L), "vkQueueSubmit2(RT GPU executor)");
            }
            submitted = true;
            synchronized (submissionLock) {
                submittedBuildValue = Math.max(submittedBuildValue, signalValue);
                submissionLock.notifyAll();
            }
            VulkanDiagnostics.setInFlight("async-compute",
                    "submitted builds=" + firstValue + ".." + signalValue + " batch=" + batch.size());
            waitTimeline(buildTimeline, signalValue);
            completed = true;
        } finally {
            // Never retry a failed host wait while unwinding: propagate its original error. A command
            // buffer is safe to release here only if submission never happened or completion was observed.
            // Otherwise the command pool owns it until shutdown waits the device idle and destroys the pool.
            if (cmd != null && (!submitted || completed)) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VK10.vkFreeCommandBuffers(ctx.vk(), commandPool, stack.pointers(cmd));
                }
            }
            if (!submitted || completed) {
                VulkanDiagnostics.setInFlight("async-compute", null);
            }
        }
    }

    private long createTimeline(String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                    .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0L);
            VkSemaphoreCreateInfo ci = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type);
            LongBuffer out = stack.mallocLong(1);
            ctx.checkDeviceResult(VK10.vkCreateSemaphore(ctx.vk(), ci, null, out), "vkCreateSemaphore(" + label + ")");
            long semaphore = out.get(0);
            RtDebugLabels.name(this.ctx, VK10.VK_OBJECT_TYPE_SEMAPHORE, semaphore, label);
            return semaphore;
        }
    }

    private void createCommandPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo ci = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                    .queueFamilyIndex(computeQueue.familyIndex());
            LongBuffer out = stack.mallocLong(1);
            ctx.checkDeviceResult(VK10.vkCreateCommandPool(ctx.vk(), ci, null, out), "vkCreateCommandPool(RT GPU executor)");
            commandPool = out.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_COMMAND_POOL, commandPool, "RT GPU executor command pool");
        }
    }

    private long queryTimeline(long semaphore) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            ctx.checkDeviceResult(VK12.vkGetSemaphoreCounterValue(ctx.vk(), semaphore, out), "vkGetSemaphoreCounterValue");
            return out.get(0);
        }
    }

    private void waitTimeline(long semaphore, long value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreWaitInfo wi = VkSemaphoreWaitInfo.calloc(stack).sType$Default()
                    .semaphoreCount(1)
                    .pSemaphores(stack.longs(semaphore))
                    .pValues(stack.longs(value));
            ctx.checkDeviceResult(VK12.vkWaitSemaphores(ctx.vk(), wi, Long.MAX_VALUE), "vkWaitSemaphores(RT GPU executor)");
        }
    }

    private void awaitBuildSubmission(long value) {
        synchronized (submissionLock) {
            while (submittedBuildValue < value && executorFailure == null) {
                try {
                    submissionLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting for RT GPU build submission " + value, e);
                }
            }
        }
        checkExecutorFailure();
    }

    public static final class Build {
        private final long value;

        private Build(long value) {
            this.value = value;
        }

        public long value() {
            return value;
        }
    }

    /** Mutable last-use owner embedded in reusable or asynchronously retired GPU resource slots. */
    public static final class TrackedGraphicsUse {
        private RtGpuExecutor owner;
        private long value;
        private GraphicsUse pending;

        /**
         * Adopt {@code graphicsUse} as this owner's newest frame, but only once that frame's commands
         * enter the host submission that signals its timeline value.
         *
         * <p>A frame reservation is allocated at {@link #beginGraphicsUse} and signalled only when
         * {@link GraphicsUse#commandsAccepted()} ran. Recording a value eagerly would let a frame that
         * never reaches submission leave a value here that the timeline never reaches, and every later
         * {@link GraphicsUseWaiter#await} on this owner would block forever. Deferring through
         * {@link GraphicsUse#whenSubmitted} means an abandoned frame simply leaves the previous value in
         * place, which is correct: its commands never ran, so they never read this resource.
         */
        public void mark(GraphicsUse graphicsUse) {
            graphicsUse.owner().assertRenderThread();
            adopt(graphicsUse);
        }

        /** The device-independent half of {@link #mark}, so its ordering is testable without a device. */
        void adopt(GraphicsUse graphicsUse) {
            if (owner != null && owner != graphicsUse.owner()) {
                throw new IllegalArgumentException("Tracked graphics use belongs to a different Vulkan device");
            }
            owner = graphicsUse.owner();
            pending = graphicsUse;
            graphicsUse.whenSubmitted(() -> {
                value = Math.max(value, graphicsUse.value());
                pending = null;
            });
        }

        long value() {
            return value;
        }

        /**
         * Run {@code action} once every mark on this owner has contributed to {@link #value}.
         *
         * <p>Retirement reads {@link #value} to choose a completion point. A mark recorded earlier in the
         * frame that is still awaiting submission has not raised it yet, so reading it directly would
         * schedule destruction against an older frame than the one already recording against this
         * resource. Submitted callbacks run in registration order, so chaining here observes the mark.
         */
        void whenMarksApplied(Runnable action) {
            if (pending == null) action.run();
            else pending.whenSubmitted(action);
        }

        /** Forget timeline ownership after the owning resources have been destroyed or synchronized. */
        public void clear() {
            if (owner != null) {
                owner.assertRenderThread();
            }
            value = 0L;
            owner = null;
            pending = null;
        }
    }

    /**
     * Reuses one timeline query while awaiting several tracked owners. Timeline values are monotonic, so
     * completing a newer value also proves every older value complete.
     */
    public final class GraphicsUseWaiter {
        private long completedValue;

        private GraphicsUseWaiter(long completedValue) {
            this.completedValue = completedValue;
        }

        /** Return true only when this call had to issue a host wait. */
        public boolean await(TrackedGraphicsUse trackedUse) {
            assertRenderThread();
            long requiredValue = trackedUse.value;
            if (requiredValue <= completedValue) {
                return false;
            }
            checkExecutorFailure();
            waitTimeline(graphicsTimeline, requiredValue);
            completedValue = requiredValue;
            return true;
        }

        private boolean awaitValue(long requiredValue) {
            if (requiredValue <= completedValue) return false;
            checkExecutorFailure();
            waitTimeline(graphicsTimeline, requiredValue);
            completedValue = requiredValue;
            return true;
        }
    }

    private record Job(BooleanSupplier cancelled, Consumer<VkCommandBuffer> record, Runnable afterSuccess,
                       BiConsumer<Build, Throwable> finished, Build build) {
    }

    private record DestroyJob(long lastUseValue, Runnable destroy) {
    }
}
