package dev.comfyfluffy.caustica.support;

import java.util.Objects;
import java.util.function.Consumer;

/** One independently closeable strong reference to a shared resource. */
public final class SharedResource<T> implements AutoCloseable {
    private State<T> state;

    private SharedResource(State<T> state) {
        this.state = state;
    }

    public static <T> SharedResource<T> owned(T value, Consumer<? super T> disposer) {
        return new SharedResource<>(new State<>(
                Objects.requireNonNull(value, "value"),
                Objects.requireNonNull(disposer, "disposer")
        ));
    }

    public synchronized T get() {
        return openState().value();
    }

    public synchronized SharedResource<T> retain() {
        State<T> current = openState();
        current.retain();
        return new SharedResource<>(current);
    }

    @Override
    public void close() {
        State<T> released;
        synchronized (this) {
            released = state;
            state = null;
        }
        if (released != null) released.release();
    }

    private State<T> openState() {
        if (state == null) throw new IllegalStateException("shared resource is closed");
        return state;
    }

    private static final class State<T> {
        private T value;
        private Consumer<? super T> disposer;
        private int references = 1;

        private State(T value, Consumer<? super T> disposer) {
            this.value = value;
            this.disposer = disposer;
        }

        private synchronized T value() {
            return value;
        }

        private synchronized void retain() {
            references++;
        }

        private void release() {
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
            if (disposedBy != null) disposedBy.accept(disposedValue);
        }
    }
}
