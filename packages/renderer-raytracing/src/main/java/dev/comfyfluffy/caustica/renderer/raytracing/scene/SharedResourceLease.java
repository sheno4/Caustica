package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import java.util.Objects;
import java.util.function.Consumer;

/** One independently closeable strong reference to a shared resource. */
final class SharedResourceLease<T> implements AutoCloseable {
    private SharedResourceState<T> state;

    private SharedResourceLease(SharedResourceState<T> state) {
        this.state = state;
    }

    static <T> SharedResourceLease<T> owned(T value, Consumer<? super T> disposer) {
        return new SharedResourceLease<>(new SharedResourceState<>(
                Objects.requireNonNull(value, "value"),
                Objects.requireNonNull(disposer, "disposer")
        ));
    }

    synchronized T get() {
        return openState().get();
    }

    synchronized SharedResourceLease<T> retain() {
        SharedResourceState<T> current = openState();
        current.retain();
        return new SharedResourceLease<>(current);
    }

    @Override
    public void close() {
        SharedResourceState<T> released;
        synchronized (this) {
            released = state;
            state = null;
        }
        if (released != null) {
            released.release();
        }
    }

    private SharedResourceState<T> openState() {
        if (state == null) {
            throw new IllegalStateException("shared resource lease is closed");
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
        return value;
    }

    synchronized void retain() {
        references++;
    }

    void release() {
        T disposedValue = null;
        Consumer<? super T> disposedBy = null;
        synchronized (this) {
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
