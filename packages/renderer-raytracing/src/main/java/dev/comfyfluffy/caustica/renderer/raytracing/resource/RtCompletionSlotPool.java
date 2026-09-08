package dev.comfyfluffy.caustica.renderer.raytracing.resource;

import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;

import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Slots become writable only after their owner returns them following all GPU uses. */
public final class RtCompletionSlotPool<T> implements AutoCloseable {
    private final ArrayList<T> available = new ArrayList<>();
    private final Consumer<T> retire;
    private boolean closed;

    public RtCompletionSlotPool(Consumer<T> retire) {
        this.retire = retire;
    }

    public synchronized T acquire(Predicate<T> fits, Supplier<T> create) {
        if (closed) throw new IllegalStateException("completion slot pool is closed");
        for (int index = 0; index < available.size(); index++) {
            if (fits.test(available.get(index))) return available.remove(index);
        }
        if (!available.isEmpty()) retire.accept(available.removeLast());
        return create.get();
    }

    /** A rejected completion registration returns the unused reservation to the pool. */
    public T acquire(Predicate<T> fits, Supplier<T> create, Consumer<Runnable> whenComplete) {
        T slot = acquire(fits, create);
        try {
            whenComplete.accept(() -> release(slot));
            return slot;
        } catch (RuntimeException | Error failure) {
            release(slot);
            throw failure;
        }
    }

    public synchronized void release(T slot) {
        if (closed) retire.accept(slot);
        else available.add(slot);
    }

    @Override public synchronized void close() {
        closed = true;
        Runnable[] releases = available.stream().<Runnable>map(slot -> () -> retire.accept(slot))
                .toArray(Runnable[]::new);
        available.clear();
        new ResourceLifetime(releases).close();
    }

    public static long capacity(int bytes) {
        return 1L << (64 - Long.numberOfLeadingZeros(Math.max(256L, bytes) - 1));
    }
}
