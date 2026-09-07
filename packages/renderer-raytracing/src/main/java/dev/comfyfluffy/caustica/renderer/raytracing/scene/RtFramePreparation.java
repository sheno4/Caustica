package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/** Bounded CPU phases whose borrowed frame inputs remain owned until every accepted task finishes. */
final class RtFramePreparation implements AutoCloseable {
    private static final com.sun.management.ThreadMXBean THREADS =
            java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
    private static final int WORKERS = 4;
    private static final int WORK_PER_CHUNK = 256;
    private final ExecutorService executor = Executors.newFixedThreadPool(WORKERS,
            Thread.ofPlatform().name("Caustica frame preparation-", 0).factory());

    static <T> List<List<T>> chunks(List<T> inputs) {
        return chunks(inputs, ignored -> 1);
    }

    static <T> List<List<T>> chunks(List<T> inputs, ToIntFunction<? super T> work) {
        if (inputs.isEmpty()) return List.of(inputs);
        long totalWork = 0L;
        for (T input : inputs) totalWork = Math.addExact(totalWork, Math.max(1, work.applyAsInt(input)));
        int count = Math.min(Math.min(WORKERS, inputs.size()),
                Math.max(1, Math.toIntExact(Math.ceilDiv(totalWork, WORK_PER_CHUNK))));
        List<List<T>> chunks = new ArrayList<>(count);
        int first = 0;
        long completedWork = 0L;
        for (int index = 1; index < count; index++) {
            long target = Math.ceilDiv(Math.multiplyExact(totalWork, index), count);
            int lastExclusive = inputs.size() - (count - index);
            int end = first;
            while (end < lastExclusive && (end == first || completedWork < target)) {
                completedWork = Math.addExact(completedWork, Math.max(1, work.applyAsInt(inputs.get(end))));
                end++;
            }
            chunks.add(inputs.subList(first, end));
            first = end;
        }
        chunks.add(inputs.subList(first, inputs.size()));
        return chunks;
    }

    <T> void run(List<T> chunks, Consumer<T> operation) {
        try (var batch = batch()) {
            for (T chunk : chunks) batch.submit(() -> operation.accept(chunk));
        }
    }

    Batch batch() {
        return new Batch();
    }

    /** Caller-owned scope for leaf tasks borrowing inputs until close has joined every accepted task. */
    final class Batch implements AutoCloseable {
        private final List<CompletableFuture<Void>> tasks = new ArrayList<>(WORKERS);

        void submit(Runnable operation) {
            tasks.add(CompletableFuture.runAsync(operation, executor));
        }

        @Override public void close() {
            Throwable failure = null;
            // Never cancel: other tasks may still be writing into borrowed mapped buffers.
            for (CompletableFuture<Void> task : tasks) {
                try {
                    task.join();
                } catch (CompletionException taskFailure) {
                    Throwable cause = taskFailure.getCause();
                    if (failure == null) failure = cause;
                    else if (failure != cause) failure.addSuppressed(cause);
                }
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IllegalStateException("frame preparation failed", failure);
        }
    }

    <T> void run(String phase, List<T> chunks, ToIntFunction<T> instanceCount, Consumer<T> operation) {
        run(chunks, chunk -> measured(phase, instanceCount.applyAsInt(chunk), 0,
                () -> operation.accept(chunk)).run());
    }

    static Runnable measured(String phase, int instanceCount, int lightCount, Runnable operation) {
        return () -> {
            FramePreparationEvent event = new FramePreparationEvent();
            if (!event.isEnabled()) {
                operation.run();
                return;
            }
            event.phase = phase;
            event.instanceCount = instanceCount;
            event.lightCount = lightCount;
            event.begin();
            long started = System.nanoTime();
            event.startedNanos = started;
            long cpuStarted = THREADS.getCurrentThreadCpuTime();
            long allocatedStarted = THREADS.getCurrentThreadAllocatedBytes();
            try {
                operation.run();
            } finally {
                event.elapsedNanos = System.nanoTime() - started;
                event.threadCpuNanos = THREADS.getCurrentThreadCpuTime() - cpuStarted;
                event.allocatedBytes = THREADS.getCurrentThreadAllocatedBytes() - allocatedStarted;
                event.end();
                event.commit();
            }
        };
    }

    @Name("dev.comfyfluffy.caustica.FramePreparation")
    @Label("Frame preparation chunk") @Category({"Caustica", "Frame"}) @StackTrace(false) @Enabled(false)
    static final class FramePreparationEvent extends Event {
        public String phase;
        public int instanceCount;
        public int lightCount;
        public long startedNanos;
        @Timespan(Timespan.NANOSECONDS)
        public long elapsedNanos;
        @Timespan(Timespan.NANOSECONDS)
        public long threadCpuNanos;
        @jdk.jfr.DataAmount(jdk.jfr.DataAmount.BYTES)
        public long allocatedBytes;
    }

    @Override public void close() {
        executor.close();
    }
}
