package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.support.SharedResource;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Storage becomes writable only after every submitted reader and history owner releases it. */
final class RtFeedbackSlots<T> implements AutoCloseable {
    private final ArrayDeque<T> available = new ArrayDeque<>();
    private final Consumer<T> recycle;
    private final Consumer<T> destroy;
    private boolean closed;

    RtFeedbackSlots(Consumer<T> recycle, Consumer<T> destroy) {
        this.recycle = recycle;
        this.destroy = destroy;
    }

    SharedResource<T> acquire(Predicate<T> compatible, Supplier<T> allocate) {
        T value;
        while (true) {
            synchronized (this) {
                if (closed) throw new IllegalStateException("feedback slots are closed");
                value = available.pollFirst();
            }
            if (value == null) {
                value = allocate.get();
                break;
            }
            if (compatible.test(value)) break;
            destroy.accept(value);
        }
        return SharedResource.owned(value, this::released);
    }

    private void released(T value) {
        try {
            recycle.accept(value);
        } catch (RuntimeException | Error failure) {
            try {
                destroy.accept(value);
            } catch (RuntimeException | Error cleanup) {
                if (failure != cleanup) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        synchronized (this) {
            if (!closed) {
                available.addLast(value);
                return;
            }
        }
        destroy.accept(value);
    }

    @Override public void close() {
        var released = new RtRevisionResources();
        synchronized (this) {
            closed = true;
            while (!available.isEmpty()) {
                T value = available.removeLast();
                released.add(() -> destroy.accept(value));
            }
        }
        released.close();
    }
}
