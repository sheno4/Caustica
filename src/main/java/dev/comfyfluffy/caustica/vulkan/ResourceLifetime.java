package dev.comfyfluffy.caustica.vulkan;

import java.util.List;

/**
 * Executes an owned resource's already-drained destruction actions once, in dependency order.
 * The owner serializes calls to close; every action is attempted even if an earlier action fails.
 */
public final class ResourceLifetime implements AutoCloseable {
    private final List<Runnable> destroy;
    private boolean closed;

    public ResourceLifetime(Runnable... destroy) {
        this.destroy = List.of(destroy);
    }

    /** Attempts every rollback action, attaching cleanup failures to the original failure. */
    public static void closeAfterFailure(Throwable failure, Runnable... destroy) {
        try {
            new ResourceLifetime(destroy).close();
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        for (Runnable action : destroy) {
            try {
                action.run();
            } catch (RuntimeException | Error next) {
                if (failure == null) failure = next;
                else if (failure != next) failure.addSuppressed(next);
            }
        }
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
    }
}
