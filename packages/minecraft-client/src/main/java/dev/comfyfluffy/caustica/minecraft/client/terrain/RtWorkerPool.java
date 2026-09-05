package dev.comfyfluffy.caustica.minecraft.client.terrain;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftOptions;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.CausticaMod;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared daemon thread pool for CPU-heavy RT work that must stay off the render thread — terrain
 * tessellation and terrain buffer/BLAS preparation, with LOD build / atlas stitching as future
 * candidates. Workers may create distinct Vulkan/VMA objects and enqueue command recording onto
 * {@code RtGpuExecutor}; they never access or submit the graphics queue. Each task delivers exactly one
 * terminal result through the terrain lifecycle barrier.
 *
 * <p>Sized at {@code -Dcaustica.rt.workerThreads} (default {@code clamp(cores/2, 1, 4)}) to leave
 * cores for Minecraft's own chunk meshers. Core threads time out when idle; all are daemon so they
 * never block JVM exit.
 */
public final class RtWorkerPool {
    private final int threads;
    private ThreadPoolExecutor exec;

    public RtWorkerPool() {
        this(resolveThreads());
    }

    RtWorkerPool(int threads) {
        if (threads <= 0) throw new IllegalArgumentException("threads must be positive");
        this.threads = threads;
    }

    private static int resolveThreads() {
        return CausticaConfig.get(MinecraftOptions.Rt.WORKER_THREADS);
    }

    private synchronized ThreadPoolExecutor executor() {
        if (exec == null) {
            ThreadFactory factory = new ThreadFactory() {
                private final AtomicInteger n = new AtomicInteger();
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "rt-worker-" + n.incrementAndGet());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            };
            ThreadPoolExecutor e = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(), factory);
            e.allowCoreThreadTimeOut(true);
            exec = e;
            CausticaMod.LOGGER.info("RT worker pool started with {} thread(s)", threads);
        }
        return exec;
    }

    /** Submit worker-owned RT preparation; completion is delivered by the task itself. */
    synchronized State state() {
        return exec == null ? new State(threads, 0, 0) : new State(threads,
                exec.getActiveCount(), exec.getQueue().size());
    }

    record State(int threads, int active, int queued) { }

    public void submit(Runnable job) {
        executor().execute(job);
    }

    /** Submit work whose owner must close its lifecycle if shutdown removes it before execution. */
    public void submit(Runnable job, Runnable cancelled) {
        executor().execute(new CancellableJob(job, cancelled));
    }

    /**
     * Stop all workers, close queued-job lifecycles, and join running jobs. Once this returns no worker
     * can enqueue additional GPU work, so the session may take a stable GPU-drain snapshot.
     */
    public synchronized void shutdown() {
        if (exec != null) {
            for (Runnable queued : exec.shutdownNow()) {
                if (queued instanceof CancellableJob job) {
                    job.cancel();
                }
            }
            try {
                exec.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping RT workers", e);
            }
            exec = null;
        }
    }

    private record CancellableJob(Runnable work, Runnable cancelled) implements Runnable {
        @Override
        public void run() {
            work.run();
        }

        void cancel() {
            cancelled.run();
        }
    }
}
