package dev.comfyfluffy.caustica.engine.vulkan;

import java.util.ArrayList;
import java.util.List;

/** Fixed-capacity native lifetime evidence, independent of recording and logging settings. */
public final class GpuCrashHistory {
    public enum Event {
        GRAPHICS_RESERVED, GRAPHICS_SIGNAL, GRAPHICS_OBSERVED, GRAPHICS_WAIT, GRAPHICS_RETIRE,
        GRAPHICS_POOL_ACCEPTED, COMPUTE_POOL_RECORD, COMPUTE_SUBMIT_BEGIN, COMPUTE_SUBMIT, COMPUTE_COMPLETED,
        POOL_CREATE, POOL_ALLOCATE, POOL_RESET, POOL_RETURN, POOL_FAILED, POOL_DESTROY,
        QUERY_UNAVAILABLE, QUERY_ERROR, HOST_SUBMIT_BEGIN, HOST_SUBMIT, HOST_RESET,
        SEMAPHORE_CREATE, SEMAPHORE_DESTROY, DEVICE_IDLE_BEGIN, DEVICE_IDLE, GRAPHICS_DRAIN
    }

    private static final GpuCrashHistory GLOBAL = new GpuCrashHistory(4096);
    private static final GpuCrashHistory ANOMALIES = new GpuCrashHistory(64);
    private final Event[] events;
    private final long[] times, threads, handles, targets, observed, details;
    private long sequence;
    private boolean frozen;

    GpuCrashHistory(int capacity) {
        events = new Event[capacity];
        times = new long[capacity];
        threads = new long[capacity];
        handles = new long[capacity];
        targets = new long[capacity];
        observed = new long[capacity];
        details = new long[capacity];
    }

    /** Fields are raw handles, timeline values and event-specific native results or counts. */
    public static void record(Event event, long handle, long target, long observation, long detail) {
        // Query anomalies remain available even when rendering continues after a recording stops.
        var history = event == Event.QUERY_UNAVAILABLE || event == Event.QUERY_ERROR ? ANOMALIES : GLOBAL;
        history.add(event, handle, target, observation, detail);
    }

    synchronized void add(Event event, long handle, long target, long observation, long detail) {
        if (frozen) return;
        int slot = (int) (sequence++ % events.length);
        events[slot] = event;
        times[slot] = System.nanoTime();
        threads[slot] = Thread.currentThread().threadId();
        handles[slot] = handle;
        targets[slot] = target;
        observed[slot] = observation;
        details[slot] = detail;
    }

    public static List<Entry> capture() { return GLOBAL.freeze(); }

    public static List<Entry> captureAnomalies() { return ANOMALIES.freeze(); }

    /** Snapshot allocation happens only on failure; cleanup cannot overwrite the triggering history. */
    synchronized List<Entry> freeze() {
        frozen = true;
        long first = Math.max(0, sequence - events.length);
        var result = new ArrayList<Entry>((int) (sequence - first));
        for (long index = first; index < sequence; index++) {
            int slot = (int) (index % events.length);
            result.add(new Entry(index, times[slot], threads[slot], events[slot], handles[slot],
                    targets[slot], observed[slot], details[slot]));
        }
        return List.copyOf(result);
    }

    public record Entry(long sequence, long nanoTime, long threadId, Event event,
                        long handle, long target, long observed, long detail) { }
}
