package dev.comfyfluffy.caustica.vulkan;

import java.util.List;

/** Executes an owned resource's already-drained destruction actions once, in dependency order. */
final class ResourceLifetime implements AutoCloseable {
    private final List<Runnable> destroy;
    private boolean closed;

    ResourceLifetime(Runnable... destroy) {
        this.destroy = List.of(destroy);
    }

    @Override
    public void close() {
        if (closed) return;
        destroy.forEach(Runnable::run);
        closed = true;
    }
}
