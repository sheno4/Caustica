package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Slots become writable only after their graphics completion callback returns them to this pool. */
final class RtTraceSlotPool<T> implements AutoCloseable {
    private final ArrayList<T> available = new ArrayList<>();
    private final Consumer<T> retire;
    private boolean closed;

    RtTraceSlotPool(Consumer<T> retire) {
        this.retire = retire;
    }

    synchronized T acquire(Predicate<T> fits, Supplier<T> create) {
        if (closed) throw new IllegalStateException("trace slot pool is closed");
        for (int index = 0; index < available.size(); index++) {
            if (fits.test(available.get(index))) return available.remove(index);
        }
        if (!available.isEmpty()) retire.accept(available.removeLast());
        return create.get();
    }

    synchronized void release(T slot) {
        if (closed) retire.accept(slot);
        else available.add(slot);
    }

    @Override public synchronized void close() {
        closed = true;
        available.forEach(retire);
        available.clear();
    }

    static long capacity(int bytes) {
        return 1L << (64 - Long.numberOfLeadingZeros(Math.max(256L, bytes) - 1));
    }
}
