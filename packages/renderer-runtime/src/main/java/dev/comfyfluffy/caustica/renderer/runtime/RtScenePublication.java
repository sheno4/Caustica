package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.support.SharedResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A serial worker prepares revisions while readers retain the last completed result.
 * The render thread owns requests and lifecycle calls; the monitor protects publication to readers.
 */
final class RtScenePublication<I extends AutoCloseable, O> implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtScenePublication.class);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("Caustica scene preparation").factory());
    private final Function<I, SharedResource<O>> prepare;
    private Request<I> pending;
    private SharedResource<O> completed;
    private Object requestedKey;
    private long generation;
    private boolean running;
    private boolean closed;
    private Throwable failure;

    RtScenePublication(Function<I, SharedResource<O>> prepare) {
        this.prepare = prepare;
    }

    /** Takes ownership even when a request is unchanged or replaces an unstarted request. */
    void request(Object key, I input) {
        I displaced = null;
        synchronized (this) {
            if (closed) throw new IllegalStateException("scene publication is closed");
            if (Objects.equals(requestedKey, key)) {
                displaced = input;
            } else {
                requestedKey = key;
                if (pending != null) displaced = pending.input;
                pending = new Request<>(generation, input);
                if (!running) {
                    running = true;
                    worker.execute(this::produce);
                }
            }
        }
        closeInput(displaced);
    }

    synchronized SharedResource<O> acquire() {
        if (failure != null && completed == null) throw new IllegalStateException("scene preparation failed", failure);
        return completed == null ? null : completed.retain();
    }

    private void produce() {
        while (true) {
            Request<I> request;
            synchronized (this) {
                request = pending;
                pending = null;
                if (request == null) {
                    running = false;
                    return;
                }
            }
            SharedResource<O> result = null;
            try (I input = request.input) {
                result = prepare.apply(input);
            } catch (Throwable error) {
                synchronized (this) {
                    if (request.generation == generation) failure = error;
                }
                LOGGER.error("Scene revision preparation failed", error);
                if (result != null) result.close();
                continue;
            }
            SharedResource<O> displaced;
            synchronized (this) {
                // Serial completion cannot replace a newer published revision. Pending edits do not
                // suppress this completed state: continuous updates must not starve readers.
                if (request.generation == generation) {
                    displaced = completed;
                    completed = result;
                    failure = null;
                } else {
                    displaced = result;
                }
            }
            if (displaced != null) displaced.close();
        }
    }

    /** Invalidates old work, then drains the worker before the caller tears down preparation resources. */
    void clear() {
        Request<I> discarded;
        SharedResource<O> released;
        synchronized (this) {
            generation++;
            requestedKey = null;
            discarded = pending;
            pending = null;
            released = completed;
            completed = null;
            failure = null;
        }
        try (released) {
            closeInput(discarded == null ? null : discarded.input);
        } finally {
            // Preparation resources must remain alive until the worker drains, even when release fails.
            if (!worker.isShutdown()) CompletableFuture.runAsync(() -> { }, worker).join();
        }
    }

    private static void closeInput(AutoCloseable input) {
        if (input == null) return;
        try {
            input.close();
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("scene input release failed", error);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        try {
            clear();
        } finally {
            worker.close();
        }
    }

    private record Request<I>(long generation, I input) { }
}
