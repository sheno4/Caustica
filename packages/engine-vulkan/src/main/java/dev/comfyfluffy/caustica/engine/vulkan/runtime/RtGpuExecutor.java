package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeJob;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.engine.vulkan.GpuCrashHistory;
import static dev.comfyfluffy.caustica.engine.vulkan.GpuCrashHistory.Event.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import jdk.jfr.*;

import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

/** Batched asynchronous compute recording and terminal job completion on a reserved queue. */
public final class RtGpuExecutor implements GpuComputeQueue {
    private static final EventType BATCH_EVENT = EventType.getEventType(ComputeBatchEvent.class);
    private final VulkanDeviceContext ctx;
    private final long jobTimeline;
    private final CommandPoolCache<VkCommandBuffer> commandPools;
    private final ExecutorService compute = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("Caustica GPU compute").factory());
    private final ConcurrentLinkedQueue<Job> jobs = new ConcurrentLinkedQueue<>();
    private long nextJobValue;
    private volatile Throwable executorFailure;
    private boolean closed;

    RtGpuExecutor(VulkanDeviceContext ctx) {
        this.ctx = ctx;
        commandPools = new CommandPoolCache<>(new VulkanCommandPoolBackend(ctx,
                ctx.computeQueue().familyIndex(), "compute"), "compute", 128);
        jobTimeline = createTimeline("GPU compute completion");
    }

    @Override
    public synchronized GpuComputeJob submit(Consumer<? super VkCommandBuffer> recorder,
                                             Consumer<? super GpuComputeCompletion> completion) {
        java.util.Objects.requireNonNull(recorder, "recorder");
        java.util.Objects.requireNonNull(completion, "completion");
        checkExecutorFailure();
        if (closed) throw new IllegalStateException("GPU executor is closed");
        AtomicBoolean cancelled = new AtomicBoolean();
        long value = ++nextJobValue;
        jobs.add(new Job(value, cancelled, recorder, completion, BATCH_EVENT.isEnabled() ? System.nanoTime() : 0));
        compute.execute(this::runBatch);
        return () -> cancelled.set(true);
    }

    private void runBatch() {
        List<Job> executable = new ArrayList<>();
        for (int count = 0; count < 128; count++) {
            Job job = jobs.poll();
            if (job == null) break;
            if (executorFailure != null) finish(job, new GpuComputeCompletion.Failed(executorFailure));
            else if (job.cancelled.get()) finish(job, new GpuComputeCompletion.Cancelled());
            else executable.add(job);
        }
        if (executable.isEmpty()) return;
        ComputeBatchEvent event = BATCH_EVENT.isEnabled() ? new ComputeBatchEvent() : null;
        if (event != null) {
            event.begin();
            event.jobs = executable.size();
            long queued = executable.getFirst().queuedNanos;
            event.oldestQueueNanos = queued == 0 ? -1 : System.nanoTime() - queued;
        }
        GpuComputeCompletion result;
        try {
            long value = executable.getLast().value;
            execute(value, executable, event);
            ctx.publishCompletedCompute(value);
            result = new GpuComputeCompletion.Succeeded();
        } catch (Throwable failure) {
            latchFailure(failure);
            result = new GpuComputeCompletion.Failed(failure);
        }
        long callbacksStarted = event == null ? 0 : System.nanoTime();
        for (Job job : executable) finish(job, result);
        if (event != null) {
            event.callbackNanos = System.nanoTime() - callbacksStarted;
            event.succeeded = result instanceof GpuComputeCompletion.Succeeded;
            event.commit();
        }
    }

    private void finish(Job job, GpuComputeCompletion result) {
        try { job.completion.accept(result); }
        catch (Throwable failure) { latchFailure(failure); }
    }

    @Override
    public int[] sharedQueueFamilyIndices() { return ctx.asyncBufferSharingQueueFamilies(); }

    public void throwIfFailed() { checkExecutorFailure(); }

    /** Wait for every accepted job's terminal callback; producers must already be stopped. */
    void drain() {
        await(compute.submit(() -> {}));
    }

    void stop() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            compute.shutdown();
        }
        awaitTermination(compute);
    }

    void destroyAfterDeviceIdle() {
        commandPools.destroyAfterDeviceIdle();
        GpuCrashHistory.record(SEMAPHORE_DESTROY, jobTimeline, nextJobValue, ctx.completedComputeValue(), 2);
        VK10.vkDestroySemaphore(ctx.vk(), jobTimeline, null);
        checkExecutorFailure();
    }

    long completionSemaphore() { return jobTimeline; }

    private static void await(Future<?> future) {
        try { future.get(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while draining GPU work", e);
        } catch (ExecutionException e) { throw new IllegalStateException("GPU drain failed", e.getCause()); }
    }

    private static void awaitTermination(ExecutorService executor) {
        try { while (!executor.awaitTermination(1, TimeUnit.DAYS)) {} }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping GPU work", e);
        }
    }

    private synchronized void latchFailure(Throwable failure) {
        if (executorFailure == null) executorFailure = failure;
        else if (executorFailure != failure) executorFailure.addSuppressed(failure);
    }

    private void checkExecutorFailure() {
        if (executorFailure != null) throw new IllegalStateException("GPU executor failed", executorFailure);
    }

    private void execute(long value, List<Job> batch, ComputeBatchEvent event) {
        long stageStarted = event == null ? 0 : System.nanoTime();
        var lease = commandPools.acquire(batch.size());
        if (event != null) { event.poolNanos = System.nanoTime() - stageStarted; stageStarted = System.nanoTime(); }
        GpuCrashHistory.record(COMPUTE_POOL_RECORD, lease.poolHandle(), value, 0, batch.size());
        boolean submitted = false;
        boolean complete = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var commands = VkCommandBufferSubmitInfo.calloc(batch.size(), stack);
            for (int index = 0; index < batch.size(); index++) {
                VkCommandBuffer command = lease.begin(index);
                batch.get(index).recorder.accept(command);
                ctx.checkDeviceResult(VK10.vkEndCommandBuffer(command), "vkEndCommandBuffer(compute)");
                commands.get(index).sType$Default().commandBuffer(command);
            }
            if (event != null) { event.recordNanos = System.nanoTime() - stageStarted; stageStarted = System.nanoTime(); }
            var signal = VkSemaphoreSubmitInfo.calloc(1, stack).sType$Default()
                    .semaphore(jobTimeline).value(value).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            var submission = VkSubmitInfo2.calloc(1, stack).sType$Default()
                    .pCommandBufferInfos(commands).pSignalSemaphoreInfos(signal);
            long prior = ctx.completedComputeValue();
            if (prior != 0L) {
                var wait = VkSemaphoreSubmitInfo.calloc(1, stack).sType$Default()
                        .semaphore(jobTimeline).value(prior).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                submission.pWaitSemaphoreInfos(wait);
            }
            synchronized (ctx.deviceQueueHostLock()) {
                GpuCrashHistory.record(COMPUTE_SUBMIT_BEGIN, jobTimeline, value, prior, ctx.computeQueue().queue().address());
                int result = VK13.vkQueueSubmit2(ctx.computeQueue().queue(), submission, 0L);
                GpuCrashHistory.record(COMPUTE_SUBMIT, jobTimeline, value, prior, result);
                ctx.checkDeviceResult(result, "vkQueueSubmit2(compute)");
            }
            submitted = true;
            if (event != null) { event.submitNanos = System.nanoTime() - stageStarted; stageStarted = System.nanoTime(); }
            waitTimeline(jobTimeline, value);
            if (event != null) event.waitNanos = System.nanoTime() - stageStarted;
            complete = true;
        } finally {
            if (complete) lease.close();
            else lease.fail();
            // Failed waits cannot permit completion callbacks to destroy resources still in execution.
            if (submitted && !complete) ctx.waitIdle();
        }
    }

    private long createTimeline(String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                    .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0L);
            var info = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type);
            var out = stack.mallocLong(1);
            ctx.checkDeviceResult(VK10.vkCreateSemaphore(ctx.vk(), info, null, out), "vkCreateSemaphore(" + label + ")");
            GpuCrashHistory.record(SEMAPHORE_CREATE, out.get(0), 0, 0, 2);
            return out.get(0);
        }
    }

    private void waitTimeline(long semaphore, long value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var wait = VkSemaphoreWaitInfo.calloc(stack).sType$Default().semaphoreCount(1)
                    .pSemaphores(stack.longs(semaphore)).pValues(stack.longs(value));
            int result = VK12.vkWaitSemaphores(ctx.vk(), wait, Long.MAX_VALUE);
            GpuCrashHistory.record(COMPUTE_COMPLETED, semaphore, value,
                    result == VK10.VK_SUCCESS ? value : -1, result);
            ctx.checkDeviceResult(result, "vkWaitSemaphores");
        }
    }

    @Name("dev.comfyfluffy.caustica.ComputeBatch")
    @Label("Asynchronous compute host batch") @Category({"Caustica", "Compute"})
    @StackTrace(false) @Enabled(false)
    static final class ComputeBatchEvent extends Event {
        int jobs;
        boolean succeeded;
        @Description("Oldest executable job's enqueue-to-batch delay; -1 if queued before recording enabled")
        @Timespan(Timespan.NANOSECONDS) long oldestQueueNanos;
        @Timespan(Timespan.NANOSECONDS) long poolNanos;
        @Timespan(Timespan.NANOSECONDS) long recordNanos;
        @Description("Host submission setup, queue host-lock acquisition, and driver submission")
        @Timespan(Timespan.NANOSECONDS) long submitNanos;
        @Description("Host timeline wait, including GPU work and host scheduling; not GPU execution time")
        @Timespan(Timespan.NANOSECONDS) long waitNanos;
        @Timespan(Timespan.NANOSECONDS) long callbackNanos;
    }

    private record Job(long value, AtomicBoolean cancelled, Consumer<? super VkCommandBuffer> recorder,
                       Consumer<? super GpuComputeCompletion> completion, long queuedNanos) {}

}
