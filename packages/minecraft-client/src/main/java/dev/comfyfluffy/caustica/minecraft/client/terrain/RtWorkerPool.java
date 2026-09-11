package dev.comfyfluffy.caustica.minecraft.client.terrain;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftOptions;

import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.CausticaMod;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;

import java.util.ArrayList;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Daemon workers for terrain tessellation and buffer/BLAS preparation, with separate serial
 * executors for retained coordination, dispatch planning, and ready publication.
 * Terrain builds hand extracted meshes to asynchronous geometry preparation. Their dispatch slots
 * remain owned until preparation completes or the queued build is cancelled.
 *
 * <p>The configured worker count leaves cores for Minecraft's own chunk meshers. Core threads time
 * out when idle; all are daemon so they never block JVM exit.
 */
public final class RtWorkerPool {
    private final int threads;
    private ThreadPoolExecutor exec;
    private ThreadPoolExecutor publications;
    private ThreadPoolExecutor planning;
    private ThreadPoolExecutor coordination;
    private boolean stopping;

    public RtWorkerPool() {
        this(CausticaConfig.get(MinecraftOptions.Rt.WORKER_THREADS));
    }

    RtWorkerPool(int threads) {
        if (threads <= 0) throw new IllegalArgumentException("threads must be positive");
        this.threads = threads;
    }

    private synchronized ThreadPoolExecutor executor() {
        if (stopping) throw new java.util.concurrent.RejectedExecutionException("terrain workers are stopping");
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

    /** Ready publication cannot queue behind terrain tessellation and uploads. */
    public synchronized void submitPublication(Runnable job, Runnable cancelled) {
        if (stopping) { cancelled.run(); return; }
        if (publications == null) {
            publications = serialExecutor("rt-terrain-publication");
        }
        publications.execute(new CancellableJob(job, cancelled));
    }

    /** Priority selection has its own lane so it cannot delay ready geometry publication. */
    synchronized void submitPlanning(Runnable job, Runnable cancelled) {
        if (stopping) { cancelled.run(); return; }
        if (planning == null) {
            planning = serialExecutor("rt-terrain-dispatch");
        }
        planning.execute(new CancellableJob(job, cancelled));
    }

    /** Retained window and request mutations run independently of extraction and edit preparation. */
    synchronized void submitCoordination(Runnable job, Runnable cancelled) {
        if (stopping) { cancelled.run(); return; }
        if (coordination == null) {
            coordination = serialExecutor("rt-terrain-coordination");
        }
        coordination.execute(new CancellableJob(job, cancelled));
    }

    private static ThreadPoolExecutor serialExecutor(String name) {
        var executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), work -> {
                    var thread = new Thread(work, name);
                    thread.setDaemon(true);
                    return thread;
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** Lifecycle callers may wait for previously queued coordination without holding terrain locks. */
    void coordinateAndWait(Runnable action) {
        var completion = new java.util.concurrent.CompletableFuture<Void>();
        submitCoordination(() -> {
            try { action.run(); completion.complete(null); }
            catch (Throwable failure) { completion.completeExceptionally(failure); }
        }, () -> completion.complete(null));
        completion.join();
    }

    /** Observe CPU worker activity without including the three serial executors. */
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
    public void shutdown() {
        ThreadPoolExecutor stoppedPlanning, stoppedPublications, stoppedCoordination, stoppedWorkers;
        synchronized (this) {
            stopping = true;
            stoppedPlanning = planning;
            stoppedPublications = publications;
            stoppedCoordination = coordination;
            stoppedWorkers = exec;
            planning = publications = coordination = exec = null;
        }
        try {
            new ResourceLifetime(() -> stop(stoppedPlanning), () -> stop(stoppedCoordination),
                    () -> stop(stoppedPublications), () -> stop(stoppedWorkers)).close();
        } finally {
            synchronized (this) { stopping = false; }
        }
    }

    private static void stop(ThreadPoolExecutor executor) {
        if (executor != null) {
            var cleanup = new ArrayList<Runnable>();
            for (Runnable queued : executor.shutdownNow()) {
                if (queued instanceof CancellableJob job) {
                    cleanup.add(job::cancel);
                }
            }
            cleanup.add(() -> join(executor));
            new ResourceLifetime(cleanup.toArray(Runnable[]::new)).close();
        }
    }

    /** Session teardown joins workers before taking a stable snapshot of accepted GPU work. */
    private static void join(ThreadPoolExecutor executor) {
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
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
