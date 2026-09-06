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
    private static final int WORKERS = 4;
    private static final int ITEMS_PER_CHUNK = 256;
    private final ExecutorService executor = Executors.newFixedThreadPool(WORKERS,
            Thread.ofPlatform().name("Caustica frame preparation-", 0).factory());

    static <T> List<List<T>> chunks(List<T> inputs) {
        int count = Math.min(WORKERS, Math.max(1, Math.ceilDiv(inputs.size(), ITEMS_PER_CHUNK)));
        List<List<T>> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            chunks.add(inputs.subList(inputs.size() * index / count, inputs.size() * (index + 1) / count));
        }
        return chunks;
    }

    <T> void run(List<T> chunks, Consumer<T> operation) {
        if (chunks.size() == 1) {
            operation.accept(chunks.getFirst());
            return;
        }
        List<CompletableFuture<Void>> tasks = new ArrayList<>(chunks.size());
        Throwable failure = null;
        try {
            for (T chunk : chunks) tasks.add(CompletableFuture.runAsync(() -> operation.accept(chunk), executor));
        } catch (Throwable dispatchFailure) {
            failure = dispatchFailure;
        }
        // Joining each accepted task, without cancellation, keeps mapped buffers alive on every exit path.
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
            try {
                operation.run();
            } finally {
                event.elapsedNanos = System.nanoTime() - started;
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
        @Timespan(Timespan.NANOSECONDS)
        public long elapsedNanos;
    }

    @Override public void close() {
        executor.close();
    }
}
