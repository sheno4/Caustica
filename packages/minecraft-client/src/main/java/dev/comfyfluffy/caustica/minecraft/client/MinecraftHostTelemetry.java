package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry;
import java.lang.management.ManagementFactory;
import java.util.function.LongSupplier;
import jdk.jfr.*;

/** Raw render-thread callback observations, independent of the renderer profile's active interval. */
public final class MinecraftHostTelemetry {
    private static final EventType LOOP_EVENT = EventType.getEventType(HostLoopEvent.class);
    private static final EventType WORK_EVENT = EventType.getEventType(HostWorkEvent.class);
    private static final EventType TOTAL_EVENT = EventType.getEventType(HostCallbackTotalsEvent.class);
    private static final EventType SUBMISSION_EVENT = EventType.getEventType(HostSubmissionEvent.class);
    private static final com.sun.management.ThreadMXBean THREADS =
            ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
    private static final Collector COLLECTOR = new Collector(System::nanoTime,
            THREADS::getCurrentThreadCpuTime, THREADS::getCurrentThreadAllocatedBytes);

    private MinecraftHostTelemetry() { }

    public static void beginLoop(long frameId) {
        COLLECTOR.beginLoop(frameId, LOOP_EVENT.isEnabled() || WORK_EVENT.isEnabled()
                || TOTAL_EVENT.isEnabled() || SUBMISSION_EVENT.isEnabled());
    }

    public static void endLoop(long frameId, boolean rtActive) {
        COLLECTOR.endLoop(frameId, rtActive);
    }

    public static RtTelemetry.Scope work(String name) {
        return COLLECTOR.work(name, WORK_EVENT.isEnabled());
    }

    public enum Callback {
        TRACKER_ROTATION, EXPECTED_CHUNKS, DIRTY_SECTION, PARTICLES, WEATHER,
        BLOCK_ENTITY, VULKAN_RESULT, SERIAL_SUBMIT
    }

    public static RtTelemetry.Scope callback(Callback callback) {
        return COLLECTOR.callback(callback, TOTAL_EVENT.isEnabled());
    }

    public interface SubmissionScope extends RtTelemetry.Scope {
        SubmissionScope NOOP = new SubmissionScope() {
            public void beforeAwait() { }
            public void afterAwait() { }
            public void completed() { }
            public void close() { }
        };
        void beforeAwait();
        void afterAwait();
        void completed();
    }

    /** All host submit work surrounding its explicit completion await. */
    public static SubmissionScope submission() {
        return COLLECTOR.submission(SUBMISSION_EVENT.isEnabled());
    }

    /** Negative MXBean samples mean unavailable, including counters disabled during a scope. */
    static long delta(long before, long after) {
        return before < 0 || after < before ? -1L : after - before;
    }

    /** One collector belongs to the host render thread; worker callbacks never contribute to it. */
    static final class Collector {
        private final LongSupplier nanos;
        private final LongSupplier cpu;
        private final LongSupplier allocated;
        private Thread owner;
        private long serial;
        private long submissionSerial;
        private long currentLoop;
        private long frameBefore;
        private long loopStart;
        private Work outer;
        private final Micro[] totals;
        private Micro micro;
        private long gapStart;
        private boolean pendingGap;

        Collector(LongSupplier nanos, LongSupplier cpu, LongSupplier allocated) {
            this.nanos = nanos;
            this.cpu = cpu;
            this.allocated = allocated;
            var callbacks = Callback.values();
            totals = new Micro[callbacks.length];
            for (int i = 0; i < callbacks.length; i++) totals[i] = new Micro(callbacks[i]);
        }

        void beginLoop(long frameId, boolean enabled) {
            if (!enabled) return;
            owner = Thread.currentThread();
            long started = nanos.getAsLong();
            if (pendingGap) flushTotals(0L, gapStart, started);
            pendingGap = false;
            currentLoop = ++serial;
            frameBefore = frameId;
            loopStart = started;
        }

        void endLoop(long frameId, boolean rtActive) {
            if (currentLoop == 0L) return;
            flushTotals(currentLoop, loopStart, nanos.getAsLong());
            HostLoopEvent event = new HostLoopEvent();
            event.loopId = currentLoop;
            event.frameIdBefore = frameBefore;
            event.frameIdAfter = frameId;
            event.rtActive = rtActive;
            event.startedNanos = loopStart;
            event.elapsedNanos = nanos.getAsLong() - loopStart;
            event.commit();
            gapStart = loopStart + event.elapsedNanos;
            pendingGap = true;
            currentLoop = 0L;
        }

        RtTelemetry.Scope work(String name, boolean enabled) {
            if (!enabled || Thread.currentThread() != owner || outer != null || micro != null) return RtTelemetry.Scope.NOOP;
            outer = new Work(name);
            return outer;
        }

        RtTelemetry.Scope callback(Callback callback, boolean enabled) {
            if (!enabled || Thread.currentThread() != owner) return RtTelemetry.Scope.NOOP;
            Micro observation = totals[callback.ordinal()];
            observation.calls++;
            if (outer != null || micro != null) return RtTelemetry.Scope.NOOP;
            micro = observation;
            observation.started = nanos.getAsLong();
            observation.allocationBefore = allocated.getAsLong();
            return observation;
        }

        SubmissionScope submission(boolean enabled) {
            if (!enabled || Thread.currentThread() != owner) return SubmissionScope.NOOP;
            return new Submission();
        }

        private void flushTotals(long loopId, long start, long end) {
            for (Micro value : totals) {
                if (TOTAL_EVENT.isEnabled()) {
                    HostCallbackTotalsEvent event = new HostCallbackTotalsEvent();
                    event.loopId = loopId;
                    event.callback = value.callback.name();
                    event.windowStartedNanos = start;
                    event.windowEndedNanos = end;
                    event.calls = value.calls;
                    event.measuredCalls = value.measuredCalls;
                    event.elapsedNanos = value.elapsed;
                    event.allocatedBytes = value.bytes;
                    event.commit();
                }
                value.calls = value.measuredCalls = value.elapsed = value.bytes = 0L;
            }
        }

        /** Reused only by the outermost measured callback on the owner thread. */
        private final class Micro implements RtTelemetry.Scope {
            private final Callback callback;
            private long calls, measuredCalls, elapsed, bytes, started, allocationBefore;

            private Micro(Callback callback) { this.callback = callback; }

            @Override public void close() {
                elapsed += nanos.getAsLong() - started;
                long delta = delta(allocationBefore, allocated.getAsLong());
                bytes = bytes < 0L || delta < 0L ? -1L : bytes + delta;
                measuredCalls++;
                micro = null;
            }
        }

        private final class Submission implements SubmissionScope {
            private final long loopId = currentLoop;
            private final long submissionId = ++submissionSerial;
            private long started = nanos.getAsLong();
            private long allocationBefore = allocated.getAsLong();
            private String segment = "beforeWait";
            private boolean running = true;
            private boolean completed;

            @Override public void beforeAwait() {
                emit();
                running = false;
            }

            @Override public void afterAwait() {
                segment = "afterWait";
                started = nanos.getAsLong();
                allocationBefore = allocated.getAsLong();
                running = true;
            }

            @Override public void completed() { completed = true; }

            @Override public void close() {
                if (running) emit();
            }

            private void emit() {
                long elapsed = nanos.getAsLong() - started;
                long bytes = delta(allocationBefore, allocated.getAsLong());
                HostSubmissionEvent event = new HostSubmissionEvent();
                event.loopId = loopId;
                event.submissionId = submissionId;
                event.segment = segment;
                event.submissionCompleted = completed;
                event.startedNanos = started;
                event.elapsedNanos = elapsed;
                event.allocatedBytes = bytes;
                event.commit();
            }
        }

        private final class Work implements RtTelemetry.Scope {
            private final String name;
            private final long loopId = currentLoop;
            private final long started = nanos.getAsLong();
            private final long cpuBefore = cpu.getAsLong();
            private final long allocationBefore = allocated.getAsLong();

            private Work(String name) { this.name = name; }

            @Override public void close() {
                long elapsed = nanos.getAsLong() - started;
                long cpuTime = delta(cpuBefore, cpu.getAsLong());
                long bytes = delta(allocationBefore, allocated.getAsLong());
                HostWorkEvent event = new HostWorkEvent();
                event.loopId = loopId;
                event.work = name;
                event.startedNanos = started;
                event.elapsedNanos = elapsed;
                event.threadCpuNanos = cpuTime;
                event.allocatedBytes = bytes;
                event.commit();
                outer = null;
            }
        }
    }

    @Name("dev.comfyfluffy.caustica.HostLoop")
    @Label("Host loop identity; includes vanilla work")
    @Category({"Caustica", "Host"}) @StackTrace(false) @Enabled(false)
    static final class HostLoopEvent extends Event {
        long loopId;
        long frameIdBefore;
        long frameIdAfter;
        boolean rtActive;
        @Label("System.nanoTime start") long startedNanos;
        @Timespan(Timespan.NANOSECONDS) long elapsedNanos;
    }

    @Name("dev.comfyfluffy.caustica.HostWork")
    @Label("Outermost mod callback; excludes intervening vanilla work")
    @Category({"Caustica", "Host"}) @StackTrace(false) @Enabled(false)
    static final class HostWorkEvent extends Event {
        @Label("Host loop; zero for work between loops") long loopId;
        String work;
        @Label("System.nanoTime start") long startedNanos;
        @Timespan(Timespan.NANOSECONDS) long elapsedNanos;
        @Timespan(Timespan.NANOSECONDS) long threadCpuNanos;
        @DataAmount(DataAmount.BYTES) long allocatedBytes;
    }

    @Name("dev.comfyfluffy.caustica.HostCallbackTotals")
    @Label("Raw callback sums; nested HostWork or callback observations suppressed")
    @Category({"Caustica", "Host"}) @StackTrace(false) @Enabled(false)
    static final class HostCallbackTotalsEvent extends Event {
        long loopId;
        String callback;
        long windowStartedNanos;
        long windowEndedNanos;
        long calls;
        long measuredCalls;
        @Timespan(Timespan.NANOSECONDS) long elapsedNanos;
        @DataAmount(DataAmount.BYTES) long allocatedBytes;
    }

    @Name("dev.comfyfluffy.caustica.HostSubmission")
    @Label("Host-inclusive submit CPU segments; explicit completion await excluded")
    @Category({"Caustica", "Host"}) @StackTrace(false) @Enabled(false)
    static final class HostSubmissionEvent extends Event {
        long loopId;
        long submissionId;
        String segment;
        @Label("Submit returned normally; recorded on the final segment") boolean submissionCompleted;
        long startedNanos;
        @Timespan(Timespan.NANOSECONDS) long elapsedNanos;
        @DataAmount(DataAmount.BYTES) long allocatedBytes;
    }
}
