package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;
import dev.comfyfluffy.caustica.engine.vulkan.GpuCrashHistory;

/** Recording-thread pool ownership with completion-thread lease returns. */
final class CommandPoolCache<C> {
    interface Backend<C> {
        long createPool();
        List<C> allocate(long pool, int count);
        void reset(long pool);
        void begin(C command);
        void destroy(long pool);
    }

    private final Backend<C> backend;
    private final String label;
    private final int maxBuffers;
    private final List<Slot<C>> slots = new ArrayList<>();
    private final ArrayDeque<Slot<C>> available = new ArrayDeque<>();
    private boolean closed;
    private long resets;
    private long destructions;
    private int allocatedBuffers;

    CommandPoolCache(Backend<C> backend, String label, int maxBuffers) {
        this.backend = backend;
        this.label = label;
        this.maxBuffers = maxBuffers;
    }

    /** Called only by the recording thread. Native calls stay outside the return lock. */
    Lease acquire(int count) {
        if (count < 1 || count > maxBuffers) throw new IllegalArgumentException("command buffer count: " + count);
        Slot<C> slot;
        synchronized (available) {
            if (closed) throw new IllegalStateException("command pool cache is closed");
            slot = available.pollFirst();
        }
        if (slot == null) {
            slot = new Slot<>(backend.createPool());
            // Retain the native handle even if allocation or recording subsequently fails.
            slots.add(slot);
        } else {
            backend.reset(slot.pool);
            resets++;
        }
        int missing = count - slot.commands.size();
        if (missing > 0) {
            slot.commands.addAll(backend.allocate(slot.pool, missing));
            allocatedBuffers += missing;
        }
        return new Lease(slot);
    }

    /** Recording and completion threads must be stopped, and the device idle. */
    void destroyAfterDeviceIdle() {
        synchronized (available) {
            if (closed) return;
            closed = true;
            available.clear();
        }
        for (Slot<C> slot : slots) {
            backend.destroy(slot.pool);
            destructions++;
        }
        if (Boolean.getBoolean("caustica.commandPools.diagnostics")) {
            LoggerFactory.getLogger(CommandPoolCache.class).info(
                    "Command pools {}: created={}, reset={}, destroyed={}, poolHighWater={}, bufferHighWater={}",
                    label, slots.size(), resets, destructions, slots.size(), allocatedBuffers);
        }
    }

    Stats stats() { return new Stats(slots.size(), resets, destructions, allocatedBuffers); }

    record Stats(int created, long resets, long destroyed, int allocatedBuffers) {}

    /** A fresh token for one recording; a stale close cannot return a later lease. */
    final class Lease implements AutoCloseable {
        private final Slot<C> slot;
        private boolean terminal;

        private Lease(Slot<C> slot) { this.slot = slot; }

        long poolHandle() { return slot.pool; }

        C begin(int index) {
            C command = slot.commands.get(index);
            try { backend.begin(command); }
            catch (Throwable failure) { fail(); throw failure; }
            return command;
        }

        /** Failed native operations retain the pool until shutdown without recycling it. */
        void fail() {
            synchronized (available) {
                if (terminal) return;
                terminal = true;
                GpuCrashHistory.record(GpuCrashHistory.Event.POOL_FAILED, slot.pool, 0, 0, slot.commands.size());
            }
        }

        /** The caller must prove abandonment or GPU completion before returning this lease. */
        @Override public void close() {
            synchronized (available) {
                if (terminal) return;
                terminal = true;
                GpuCrashHistory.record(GpuCrashHistory.Event.POOL_RETURN, slot.pool, 0, 0, slot.commands.size());
                if (!closed) available.addLast(slot);
            }
        }
    }

    private static final class Slot<C> {
        final long pool;
        final ArrayList<C> commands = new ArrayList<>();
        Slot(long pool) { this.pool = pool; }
    }
}
