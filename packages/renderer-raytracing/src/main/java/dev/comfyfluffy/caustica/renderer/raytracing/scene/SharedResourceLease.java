package dev.comfyfluffy.caustica.renderer.raytracing.scene;

/** A movable strong reference obtained from a {@link SharedResourceOwner}. */
final class SharedResourceLease<T> implements AutoCloseable {
    private SharedResourceState<T> state;

    SharedResourceLease(SharedResourceState<T> state) {
        this.state = state;
    }

    synchronized T get() {
        return openState().get();
    }

    synchronized SharedResourceLease<T> retain() {
        SharedResourceState<T> current = openState();
        current.retain();
        return new SharedResourceLease<>(current);
    }

    synchronized SharedResourceLease<T> transfer() {
        SharedResourceState<T> current = openState();
        state = null;
        return new SharedResourceLease<>(current);
    }

    @Override
    public void close() {
        SharedResourceState<T> released;
        synchronized (this) {
            released = openState();
            state = null;
        }
        released.release();
    }

    private SharedResourceState<T> openState() {
        if (state == null) {
            throw new IllegalStateException("shared resource lease is closed");
        }
        return state;
    }
}
