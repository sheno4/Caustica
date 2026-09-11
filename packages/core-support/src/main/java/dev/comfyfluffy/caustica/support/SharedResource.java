package dev.comfyfluffy.caustica.support;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * One independently closeable strong reference to a shared resource.
 * The final close invokes the disposer on the closing thread; the disposer arranges any required
 * retirement or thread dispatch.
 */
public final class SharedResource<T> implements AutoCloseable {
    private Reference<T> state;

    private SharedResource(Reference<T> state) {
        this.state = state;
    }

    public static <T> SharedResource<T> owned(T value, Consumer<? super T> disposer) {
        return new SharedResource<>(new Reference<>(
                Objects.requireNonNull(value, "value"),
                Objects.requireNonNull(disposer, "disposer")
        ));
    }

    public synchronized boolean isOpen() { return state != null; }

    /** Borrows the value while this or another strong owner remains open. */
    public synchronized T get() {
        return openState().value();
    }

    public synchronized SharedResource<T> retain() {
        return openState().retain();
    }

    /** Non-owning identity which can acquire a claim while any strong owner remains. */
    public synchronized Reference<T> reference() {
        return openState();
    }

    @Override
    public void close() {
        Reference<T> released;
        synchronized (this) {
            released = state;
            state = null;
        }
        if (released != null) released.release();
    }

    private Reference<T> openState() {
        if (state == null) throw new IllegalStateException("shared resource is closed");
        return state;
    }

    public static final class Reference<T> {
        private T value;
        private Consumer<? super T> disposer;
        private int references = 1;

        private Reference(T value, Consumer<? super T> disposer) {
            this.value = value;
            this.disposer = disposer;
        }

        private synchronized T value() {
            return value;
        }

        public synchronized boolean isAlive() {
            return references != 0;
        }

        public synchronized SharedResource<T> retain() {
            if (references == 0) throw new IllegalStateException("shared resource is released");
            references++;
            return new SharedResource<>(this);
        }

        private void release() {
            T disposedValue;
            Consumer<? super T> disposedBy;
            synchronized (this) {
                if (--references != 0) return;
                disposedValue = value;
                disposedBy = disposer;
                value = null;
                disposer = null;
            }
            // Final release may close dependencies or call a provider; neither runs under our lock.
            disposedBy.accept(disposedValue);
        }
    }
}
