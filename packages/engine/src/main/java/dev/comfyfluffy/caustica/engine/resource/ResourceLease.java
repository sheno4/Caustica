package dev.comfyfluffy.caustica.engine.resource;

import dev.comfyfluffy.caustica.api.resource.ResourceRef;

import java.util.Optional;

/** One engine-owned borrow of an immutable resource generation. */
public final class ResourceLease implements AutoCloseable {
    private final boolean noResource;
    private ResourceDirectory.ResourceState state;

    ResourceLease(ResourceDirectory.ResourceState state) {
        this(state, false);
    }

    private ResourceLease(ResourceDirectory.ResourceState state, boolean noResource) {
        this.state = state;
        this.noResource = noResource;
    }

    static ResourceLease none() {
        return new ResourceLease(null, true);
    }

    /** Acquire a trusted reference through its owning render session while latching a snapshot. */
    public static Optional<ResourceLease> tryAcquire(ResourceRef reference) {
        return ResourceDirectory.tryAcquireTrusted(reference);
    }

    /** Create another engine borrow of the same generation. */
    public synchronized ResourceLease retain() {
        ResourceDirectory.ResourceState current = state;
        if (current == null) {
            if (noResource) return none();
            throw new IllegalStateException("resource lease is closed");
        }
        return current.directory.retain(current);
    }

    @Override
    public synchronized void close() {
        ResourceDirectory.ResourceState released = state;
        if (released == null) return;
        state = null;
        released.directory.release(released);
    }
}
