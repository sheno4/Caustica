package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

/** Graphics command ownership, reusable allocation waits, and off-thread retirement. */
public final class GraphicsQueue {
    private final VulkanDeviceContext ctx;
    private final long graphicsTimeline;
    private final ScheduledExecutorService retirement = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Caustica graphics retirement");
        thread.setDaemon(true);
        return thread;
    });
    private final ArrayList<DestroyJob> destroyJobs = new ArrayList<>();
    private final AtomicLong latestSubmittedGraphicsValue = new AtomicLong();
    private long nextGraphicsValue;
    private volatile Throwable executorFailure;

    GraphicsQueue(VulkanDeviceContext ctx) {
        this.ctx = ctx;
        graphicsTimeline = createTimeline("Graphics completion");
        retirement.scheduleWithFixedDelay(this::pollRetirement, 1, 2, TimeUnit.MILLISECONDS);
    }

    void shutdownAfterDeviceIdle() {
        drainAfterDeviceIdle();
        retirement.shutdown();
        awaitTermination(retirement);
        VK10.vkDestroySemaphore(ctx.vk(), graphicsTimeline, null);
        checkExecutorFailure();
    }

    public GraphicsUse beginGraphicsUse() {
        assertRenderThread();
        checkExecutorFailure();
        return new GraphicsUse(this, ++nextGraphicsValue);
    }

    public void resolveGraphicsUse(GraphicsSubmission submission, GraphicsUse use) {
        assertRenderThread();
        if (use.owner() != this) throw new IllegalArgumentException("Graphics use belongs to another device");
        use.resolveSubmission(() -> {
            enqueueGraphicsSignal(submission, graphicsTimeline, use.value());
            latestSubmittedGraphicsValue.set(use.value());
        });
    }

    static void enqueueGraphicsSignal(GraphicsSubmission submission, long semaphore, long value) {
        submission.signalSemaphore(semaphore, value, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
    }

    public GraphicsUseWaiter graphicsUseWaiter() {
        assertRenderThread();
        checkExecutorFailure();
        return new GraphicsUseWaiter(queryTimeline(graphicsTimeline));
    }

    public void retireAfterGraphics(TrackedGraphicsUse trackedUse, Runnable destroy) {
        assertRenderThread();
        trackedUse.whenMarksApplied(() -> keepAliveAfterGraphicsValue(trackedUse.value, destroy));
    }

    public void retireAfterLatestSubmittedGraphics(Runnable destroy) {
        checkExecutorFailure();
        keepAliveAfterGraphicsValue(latestSubmittedGraphicsValue.get(), destroy);
    }

    void keepAliveAfterGraphicsValue(long value, Runnable release) {
        synchronized (destroyJobs) { destroyJobs.add(new DestroyJob(value, release)); }
    }

    void releaseAbandoned(Runnable release) {
        keepAliveAfterGraphicsValue(0L, release);
    }

    private void pollRetirement() {
        try { processRetirement(queryTimeline(graphicsTimeline)); }
        catch (Throwable failure) { latchFailure(failure); }
    }

    private void processRetirement(long completed) {
        List<Runnable> ready = new ArrayList<>();
        synchronized (destroyJobs) {
            var iterator = destroyJobs.iterator();
            while (iterator.hasNext()) {
                DestroyJob job = iterator.next();
                if (job.value <= completed) {
                    iterator.remove();
                    ready.add(job.release);
                }
            }
        }
        for (Runnable release : ready) {
            try { release.run(); }
            catch (Throwable failure) { latchFailure(failure); }
        }
    }

    void drainAfterDeviceIdle() {
        await(retirement.submit(() -> {
            while (true) {
                processRetirement(Long.MAX_VALUE);
                synchronized (destroyJobs) { if (destroyJobs.isEmpty()) break; }
            }
        }));
    }

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

    private void assertRenderThread() { ctx.backend().assertRenderThread(); }

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

    private long queryTimeline(long semaphore) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var out = stack.mallocLong(1);
            ctx.checkDeviceResult(VK12.vkGetSemaphoreCounterValue(ctx.vk(), semaphore, out), "vkGetSemaphoreCounterValue");
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

    /** Mutable last-use owner embedded in reusable or asynchronously retired GPU resource slots. */
    public static final class TrackedGraphicsUse {
        private GraphicsQueue owner;
        private long value;
        private GraphicsUse pending;

        /** Adopt a frame's timeline value only if its commands are accepted for execution. */
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
            });
            graphicsUse.whenResolved(() -> pending = null);
        }

        long value() {
            return value;
        }

        /** Retirement observes accepted marks; abandonment preserves the prior completion value. */
        void whenMarksApplied(Runnable action) {
            if (pending == null) action.run();
            else pending.whenResolved(action);
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

    }

    private record DestroyJob(long value, Runnable release) {}
}
