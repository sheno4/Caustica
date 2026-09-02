package dev.comfyfluffy.caustica.engine.compute;

import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeJob;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** Contribution-scoped compute jobs and serialized public completion delivery. */
public final class ComputeQueueSession implements AutoCloseable {
    private final GpuComputeQueue backend;
    private final Consumer<Throwable> failures;
    private final Set<ComputeContributionQueue> channels =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final ConcurrentLinkedQueue<CompletionEvent> completions = new ConcurrentLinkedQueue<>();
    private final Object progressLock = new Object();
    private boolean closed;

    public ComputeQueueSession(GpuComputeQueue backend, Consumer<Throwable> failures) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    public synchronized ComputeContributionQueue openChannel() {
        if (closed) throw new IllegalStateException("compute queue session is closed");
        ComputeContributionQueue channel = new ComputeContributionQueue(this);
        channels.add(channel);
        return channel;
    }

    GpuComputeJob submit(Task task, Consumer<? super VkCommandBuffer> recorder) {
        return backend.submit(recorder, completion -> {
            completions.add(new CompletionEvent(task, completion));
            task.completionAvailable();
        });
    }

    int[] sharedQueueFamilyIndices() {
        return backend.sharedQueueFamilyIndices();
    }

    /** Deliver completed jobs on the session-progress thread. */
    public void progress() {
        synchronized (progressLock) {
            for (CompletionEvent completion; (completion = completions.poll()) != null;) {
                completion.task.complete(completion.completion, failures);
            }
        }
    }

    synchronized void closeChannel(ComputeContributionQueue channel) {
        channels.remove(channel);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        if (!channels.isEmpty() || !completions.isEmpty()) {
            throw new IllegalStateException("compute contribution queues remain live");
        }
        closed = true;
    }

    private record CompletionEvent(Task task, GpuComputeCompletion completion) { }

    /** Owner-scoped public queue. */
    public static final class ComputeContributionQueue implements GpuComputeQueue {
        private final ComputeQueueSession session;
        private final Set<Task> tasks = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean accepting = true;
        private boolean completionAvailable;
        private boolean closed;

        ComputeContributionQueue(ComputeQueueSession session) {
            this.session = session;
        }

        @Override
        public synchronized GpuComputeJob submit(
                Consumer<? super VkCommandBuffer> recorder,
                Consumer<? super GpuComputeCompletion> completion) {
            if (!accepting) throw new IllegalStateException("compute queue no longer accepts jobs");
            Objects.requireNonNull(recorder, "recorder");
            Task task = new Task(this, Objects.requireNonNull(completion, "completion"));
            tasks.add(task);
            try {
                task.attach(session.submit(task, recorder));
                return task;
            } catch (Throwable failure) {
                tasks.remove(task);
                notifyAll();
                throw failure;
            }
        }

        @Override
        public int[] sharedQueueFamilyIndices() {
            return session.sharedQueueFamilyIndices();
        }

        public synchronized void quiesce() {
            accepting = false;
        }

        public void invalidate() {
            Task[] pending;
            synchronized (this) {
                quiesce();
                pending = tasks.toArray(Task[]::new);
            }
            for (Task task : pending) task.close();
        }

        public void drain() {
            invalidate();
            while (true) {
                session.progress();
                synchronized (this) {
                    if (tasks.isEmpty()) return;
                    if (completionAvailable) {
                        completionAvailable = false;
                        continue;
                    }
                    try {
                        wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted while draining compute jobs", interrupted);
                    }
                }
            }
        }

        public synchronized void closeChannel() {
            if (closed) return;
            if (!tasks.isEmpty()) throw new IllegalStateException("compute jobs remain live");
            closed = true;
            session.closeChannel(this);
        }

        void completed(Task task) {
            synchronized (this) {
                tasks.remove(task);
                notifyAll();
            }
        }
    }

    private static final class Task implements GpuComputeJob {
        private final ComputeContributionQueue channel;
        private Consumer<? super GpuComputeCompletion> completion;
        private GpuComputeJob backend;
        private boolean cancellationRequested;
        private boolean completed;

        Task(ComputeContributionQueue channel,
             Consumer<? super GpuComputeCompletion> completion) {
            this.channel = channel;
            this.completion = completion;
        }

        synchronized void attach(GpuComputeJob backend) {
            if (this.backend != null) throw new IllegalStateException("compute job is already attached");
            this.backend = Objects.requireNonNull(backend, "backend");
            if (cancellationRequested) backend.close();
        }

        @Override
        public synchronized void close() {
            if (completed || cancellationRequested) return;
            cancellationRequested = true;
            if (backend != null) backend.close();
        }

        void complete(GpuComputeCompletion result, Consumer<Throwable> failures) {
            Consumer<? super GpuComputeCompletion> callback;
            synchronized (this) {
                if (completed) throw new IllegalStateException("compute job completed more than once");
                completed = true;
                callback = completion;
                completion = null;
                backend = null;
            }
            try {
                callback.accept(Objects.requireNonNull(result, "result"));
            } catch (Throwable failure) {
                failures.accept(failure);
            } finally {
                channel.completed(this);
            }
        }

        void completionAvailable() {
            synchronized (channel) {
                channel.completionAvailable = true;
                channel.notifyAll();
            }
        }
    }
}
