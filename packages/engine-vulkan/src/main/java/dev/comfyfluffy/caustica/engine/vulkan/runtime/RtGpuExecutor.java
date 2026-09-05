package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeJob;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.engine.vulkan.VulkanDiagnostics;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

/** Batched asynchronous compute recording and terminal job completion on a reserved queue. */
public final class RtGpuExecutor implements GpuComputeQueue {
    private final VulkanDeviceContext ctx;
    private final long jobTimeline;
    private final ExecutorService compute = Executors.newSingleThreadExecutor(r -> daemon(r, "Caustica GPU compute"));
    private final ConcurrentLinkedQueue<Job> jobs = new ConcurrentLinkedQueue<>();
    private long nextJobValue;
    private volatile Throwable executorFailure;
    private boolean closed;

    RtGpuExecutor(VulkanDeviceContext ctx) {
        this.ctx = ctx;
        jobTimeline = createTimeline("GPU compute completion");
    }

    private static Thread daemon(Runnable action, String name) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        return thread;
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
        jobs.add(new Job(value, cancelled, recorder, completion));
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
        GpuComputeCompletion result;
        try {
            long value = executable.getLast().value;
            execute(value, executable);
            ctx.publishCompletedCompute(value);
            result = new GpuComputeCompletion.Succeeded();
        } catch (Throwable failure) {
            latchFailure(failure);
            result = new GpuComputeCompletion.Failed(failure);
        }
        for (Job job : executable) finish(job, result);
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

    private void execute(long value, List<Job> batch) {
        long pool = 0L;
        boolean submitted = false;
        boolean complete = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                    .queueFamilyIndex(ctx.computeQueue().familyIndex());
            var handle = stack.mallocLong(1);
            ctx.checkDeviceResult(VK10.vkCreateCommandPool(ctx.vk(), poolInfo, null, handle), "vkCreateCommandPool(compute)");
            pool = handle.get(0);
            var allocate = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(pool).level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(batch.size());
            var pointers = stack.mallocPointer(batch.size());
            ctx.checkDeviceResult(VK10.vkAllocateCommandBuffers(ctx.vk(), allocate, pointers), "vkAllocateCommandBuffers(compute)");
            var commands = VkCommandBufferSubmitInfo.calloc(batch.size(), stack);
            for (int index = 0; index < batch.size(); index++) {
                VkCommandBuffer command = new VkCommandBuffer(pointers.get(index), ctx.vk());
                var begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                        .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                ctx.checkDeviceResult(VK10.vkBeginCommandBuffer(command, begin), "vkBeginCommandBuffer(compute)");
                batch.get(index).recorder.accept(command);
                ctx.checkDeviceResult(VK10.vkEndCommandBuffer(command), "vkEndCommandBuffer(compute)");
                commands.get(index).sType$Default().commandBuffer(command);
            }
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
            VulkanDiagnostics.noteQueueSubmission(ctx.computeQueue().queue(), "Caustica compute queue");
            synchronized (ctx.deviceQueueHostLock()) {
                ctx.checkDeviceResult(VK13.vkQueueSubmit2(ctx.computeQueue().queue(), submission, 0L), "vkQueueSubmit2(compute)");
            }
            submitted = true;
            waitTimeline(jobTimeline, value);
            complete = true;
        } finally {
            // Failed waits cannot permit completion callbacks to destroy resources still in execution.
            if (submitted && !complete) ctx.waitIdle();
            if (pool != 0L) VK10.vkDestroyCommandPool(ctx.vk(), pool, null);
        }
    }

    private long createTimeline(String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                    .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0L);
            var info = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type);
            var out = stack.mallocLong(1);
            ctx.checkDeviceResult(VK10.vkCreateSemaphore(ctx.vk(), info, null, out), "vkCreateSemaphore(" + label + ")");
            return out.get(0);
        }
    }

    private void waitTimeline(long semaphore, long value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var wait = VkSemaphoreWaitInfo.calloc(stack).sType$Default().semaphoreCount(1)
                    .pSemaphores(stack.longs(semaphore)).pValues(stack.longs(value));
            ctx.checkDeviceResult(VK12.vkWaitSemaphores(ctx.vk(), wait, Long.MAX_VALUE), "vkWaitSemaphores");
        }
    }

    private record Job(long value, AtomicBoolean cancelled, Consumer<? super VkCommandBuffer> recorder,
                       Consumer<? super GpuComputeCompletion> completion) {}

}
