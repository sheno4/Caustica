package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Owns the initial strong reference to a resource shared with independently
 * scoped leases.
 */
final class SharedResourceOwner<T> implements AutoCloseable {
    private SharedResourceState<T> state;

    SharedResourceOwner(T value, Consumer<? super T> disposer) {
        state = new SharedResourceState<>(
                Objects.requireNonNull(value, "value"),
                Objects.requireNonNull(disposer, "disposer")
        );
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
            throw new IllegalStateException("shared resource owner is closed");
        }
        return state;
    }
}

final class SharedResourceState<T> {
    private T value;
    private Consumer<? super T> disposer;
    private int references = 1;

    SharedResourceState(T value, Consumer<? super T> disposer) {
        this.value = value;
        this.disposer = disposer;
    }

    synchronized T get() {
        if (references == 0) {
            throw new IllegalStateException("shared resource is disposed");
        }
        return value;
    }

    synchronized void retain() {
        if (references == 0) {
            throw new IllegalStateException("shared resource is disposed");
        }
        references++;
    }

    void release() {
        T disposedValue = null;
        Consumer<? super T> disposedBy = null;
        synchronized (this) {
            if (references == 0) {
                throw new IllegalStateException("shared resource is already disposed");
            }
            references--;
            if (references == 0) {
                disposedValue = value;
                disposedBy = disposer;
                value = null;
                disposer = null;
            }
        }
        if (disposedBy != null) {
            disposedBy.accept(disposedValue);
        }
    }
}
