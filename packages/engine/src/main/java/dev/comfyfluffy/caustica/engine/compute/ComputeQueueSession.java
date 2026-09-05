package dev.comfyfluffy.caustica.engine.compute;

import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeJob;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** Contribution ownership and serialized delivery around the platform compute service. */
public final class ComputeQueueSession implements AutoCloseable {
    private final GpuComputeQueue backend;
    private final Consumer<Throwable> failures;
    private final Set<ComputeContributionQueue> channels = new HashSet<>();
    private final ConcurrentLinkedQueue<Runnable> completions = new ConcurrentLinkedQueue<>();
    private final Object progressLock = new Object();
    private boolean closed;

    public ComputeQueueSession(GpuComputeQueue backend, Consumer<Throwable> failures) {
        this.backend = Objects.requireNonNull(backend);
        this.failures = Objects.requireNonNull(failures);
    }

    public synchronized ComputeContributionQueue openChannel() {
        if (closed) throw new IllegalStateException("compute session is closed");
        var channel = new ComputeContributionQueue();
        channels.add(channel);
        return channel;
    }

    public void progress() {
        synchronized (progressLock) {
            for (Runnable complete; (complete = completions.poll()) != null;) complete.run();
        }
    }

    @Override
    public synchronized void close() {
        if (!channels.isEmpty() || !completions.isEmpty()) throw new IllegalStateException("compute channels remain live");
        closed = true;
    }

    public final class ComputeContributionQueue implements GpuComputeQueue {
        private final Set<Task> tasks = new HashSet<>();
        private boolean accepting = true;
        private boolean completionAvailable;

        @Override
        public synchronized GpuComputeJob submit(Consumer<? super VkCommandBuffer> recorder,
                                                  Consumer<? super GpuComputeCompletion> callback) {
            if (!accepting) throw new IllegalStateException("compute queue no longer accepts jobs");
            Objects.requireNonNull(recorder);
            Objects.requireNonNull(callback);
            Task task = new Task();
            tasks.add(task);
            try {
                task.backendJob = backend.submit(recorder, result -> {
                    synchronized (this) {
                        completions.add(() -> deliver(task, callback, result));
                        completionAvailable = true;
                        notifyAll();
                    }
                });
                return task;
            } catch (Throwable failure) {
                tasks.remove(task);
                throw failure;
            }
        }

        private void deliver(Task task, Consumer<? super GpuComputeCompletion> callback,
                             GpuComputeCompletion result) {
            synchronized (this) { task.backendJob = null; }
            try { callback.accept(result); }
            catch (Throwable failure) { failures.accept(failure); }
            finally {
                synchronized (this) {
                    tasks.remove(task);
                    notifyAll();
                }
            }
        }

        @Override public int[] sharedQueueFamilyIndices() { return backend.sharedQueueFamilyIndices(); }

        public synchronized void quiesce() { accepting = false; }

        public void invalidate() {
            Task[] pending;
            synchronized (this) {
                accepting = false;
                pending = tasks.toArray(Task[]::new);
            }
            for (Task task : pending) task.close();
        }

        /** Cancellation never releases an accepted job before its terminal callback returns. */
        public void drain() {
            invalidate();
            while (true) {
                progress();
                synchronized (this) {
                    if (tasks.isEmpty()) return;
                    if (completionAvailable) {
                        completionAvailable = false;
                        continue;
                    }
                    try { wait(); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted while draining compute jobs", interrupted);
                    }
                }
            }
        }

        public synchronized void closeChannel() {
            if (!tasks.isEmpty()) throw new IllegalStateException("compute jobs remain live");
            accepting = false;
            synchronized (ComputeQueueSession.this) { channels.remove(this); }
        }

        private final class Task implements GpuComputeJob {
            private GpuComputeJob backendJob;
            @Override public void close() {
                synchronized (ComputeContributionQueue.this) {
                    if (backendJob != null) backendJob.close();
                }
            }
        }
    }
}
