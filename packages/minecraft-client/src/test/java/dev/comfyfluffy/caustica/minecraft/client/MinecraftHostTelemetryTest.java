package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class MinecraftHostTelemetryTest {
    @Test
    void callbackTotalsRetainZeroCountsSuppressNestedCostAndSeparateGaps(@TempDir Path dir) throws Exception {
        var nanos = new AtomicLong(100);
        var allocations = new AtomicLong(1000);
        var collector = new MinecraftHostTelemetry.Collector(nanos::get, () -> -1L, allocations::get);
        Path file = dir.resolve("totals.jfr");
        try (var recording = new Recording()) {
            recording.enable("dev.comfyfluffy.caustica.HostCallbackTotals");
            recording.enable("dev.comfyfluffy.caustica.HostWork");
            recording.start();
            collector.beginLoop(1, true);
            var reusable = collector.callback(MinecraftHostTelemetry.Callback.TRACKER_ROTATION, true);
            nanos.set(120);
            allocations.set(1016);
            reusable.close();
            try (var outer = collector.work("capture", true)) {
                assertSame(RtTelemetry.Scope.NOOP,
                        collector.callback(MinecraftHostTelemetry.Callback.VULKAN_RESULT, true));
                nanos.set(140);
                allocations.set(1032);
            }
            try (var unknown = collector.callback(MinecraftHostTelemetry.Callback.DIRTY_SECTION, true)) {
                nanos.set(145);
                allocations.set(-1);
            }
            allocations.set(50);
            try (var later = collector.callback(MinecraftHostTelemetry.Callback.DIRTY_SECTION, true)) {
                nanos.set(150);
                allocations.set(60);
            }
            try (var outer = collector.callback(MinecraftHostTelemetry.Callback.PARTICLES, true)) {
                assertSame(RtTelemetry.Scope.NOOP, collector.work("nested", true));
                assertSame(RtTelemetry.Scope.NOOP,
                        collector.callback(MinecraftHostTelemetry.Callback.WEATHER, true));
                nanos.set(160);
                allocations.set(80);
            }
            collector.endLoop(2, true);
            try (var gap = collector.callback(MinecraftHostTelemetry.Callback.WEATHER, true)) {
                nanos.set(170);
                allocations.set(88);
            }
            nanos.set(180);
            collector.beginLoop(2, true);
            assertSame(reusable, collector.callback(MinecraftHostTelemetry.Callback.TRACKER_ROTATION, true));
            reusable.close();
            collector.endLoop(3, true);
            recording.stop();
            recording.dump(file);
        }
        var totals = named(RecordingFile.readAllEvents(file), "HostCallbackTotals");
        assertEquals(MinecraftHostTelemetry.Callback.values().length * 3, totals.size());
        var tracker = total(totals, 1, "TRACKER_ROTATION");
        assertEquals(1, tracker.getLong("calls"));
        assertEquals(20, tracker.getLong("elapsedNanos"));
        assertEquals(16, tracker.getLong("allocatedBytes"));
        assertEquals(-1, total(totals, 1, "DIRTY_SECTION").getLong("allocatedBytes"));
        assertEquals(2, total(totals, 1, "DIRTY_SECTION").getLong("measuredCalls"));
        assertEquals(1, total(totals, 1, "VULKAN_RESULT").getLong("calls"));
        assertEquals(0, total(totals, 1, "VULKAN_RESULT").getLong("measuredCalls"));
        assertEquals(0, total(totals, 1, "VULKAN_RESULT").getLong("allocatedBytes"));
        assertEquals(0, total(totals, 1, "BLOCK_ENTITY").getLong("calls"));
        var gap = total(totals, 0, "WEATHER");
        assertEquals(8, gap.getLong("allocatedBytes"));
        assertEquals(160, gap.getLong("windowStartedNanos"));
        assertEquals(180, gap.getLong("windowEndedNanos"));
        assertEquals(0, total(totals, 2, "DIRTY_SECTION").getLong("allocatedBytes"));
    }

    @Test
    void submissionSegmentsExcludeWaitTimeAndAllocationsButIncludePostWaitCleanup(@TempDir Path dir) throws Exception {
        var nanos = new AtomicLong(100);
        var allocations = new AtomicLong(10);
        var collector = new MinecraftHostTelemetry.Collector(nanos::get, () -> -1L, allocations::get);
        Path file = dir.resolve("submission.jfr");
        try (var recording = new Recording()) {
            recording.enable("dev.comfyfluffy.caustica.HostSubmission");
            recording.start();
            collector.beginLoop(1, true);
            try (var submission = collector.submission(true)) {
                nanos.set(120);
                allocations.set(42);
                submission.beforeAwait();
                nanos.set(10000);
                allocations.set(9000);
                submission.afterAwait();
                nanos.set(10030);
                allocations.set(9012);
                submission.completed();
            }
            collector.endLoop(2, true);
            recording.stop();
            recording.dump(file);
        }
        var segments = named(RecordingFile.readAllEvents(file), "HostSubmission");
        assertEquals(List.of("beforeWait", "afterWait"), segments.stream().map(e -> e.getString("segment")).toList());
        assertEquals(List.of(20L, 30L), segments.stream().map(e -> e.getLong("elapsedNanos")).toList());
        assertEquals(List.of(32L, 12L), segments.stream().map(e -> e.getLong("allocatedBytes")).toList());
        assertEquals(segments.getFirst().getLong("submissionId"), segments.getLast().getLong("submissionId"));
        assertFalse(segments.getFirst().getBoolean("submissionCompleted"));
        assertTrue(segments.getLast().getBoolean("submissionCompleted"));
        assertEquals(1, segments.getFirst().getLong("loopId"));
    }

    @Test
    void failedAwaitDoesNotClaimCompleteSubmissionAndLaterSubmissionHasFreshIdentity(@TempDir Path dir) throws Exception {
        var nanos = new AtomicLong(100);
        var allocations = new AtomicLong(-1);
        var collector = new MinecraftHostTelemetry.Collector(nanos::get, () -> -1L, allocations::get);
        Path file = dir.resolve("submission-failure.jfr");
        var failure = new IllegalStateException("await failed");
        try (var recording = new Recording()) {
            recording.enable("dev.comfyfluffy.caustica.HostSubmission");
            recording.start();
            collector.beginLoop(1, true);
            var caught = assertThrows(IllegalStateException.class, () -> {
                try (var submission = collector.submission(true)) {
                    nanos.set(120);
                    submission.beforeAwait();
                    try {
                        nanos.set(5000);
                        throw failure;
                    } finally {
                        submission.afterAwait();
                    }
                }
            });
            assertSame(failure, caught);
            try (var submission = collector.submission(true)) {
                submission.beforeAwait();
                submission.afterAwait();
                submission.completed();
            }
            collector.endLoop(2, true);
            recording.stop();
            recording.dump(file);
        }
        var events = named(RecordingFile.readAllEvents(file), "HostSubmission");
        assertEquals(4, events.size());
        assertFalse(events.get(1).getBoolean("submissionCompleted"));
        assertTrue(events.get(3).getBoolean("submissionCompleted"));
        assertNotEquals(events.get(1).getLong("submissionId"), events.get(3).getLong("submissionId"));
        assertTrue(events.stream().allMatch(e -> e.getLong("allocatedBytes") == -1));
    }

    private static RecordedEvent total(List<RecordedEvent> events, long loop, String callback) {
        return events.stream().filter(e -> e.getLong("loopId") == loop && e.getString("callback").equals(callback))
                .findFirst().orElseThrow();
    }
    @Test
    void outerCallbacksHaveOneAllocationDeltaAndLoopIdentityAcrossRendererBoundaries(@TempDir Path dir) throws Exception {
        var nanos = new AtomicLong(100);
        var cpu = new AtomicLong(-1);
        var allocations = new AtomicLong(200);
        var collector = new MinecraftHostTelemetry.Collector(nanos::get, cpu::get, allocations::get);
        Path file = dir.resolve("host.jfr");
        try (var recording = new Recording()) {
            recording.enable("dev.comfyfluffy.caustica.HostLoop");
            recording.enable("dev.comfyfluffy.caustica.HostWork");
            recording.start();
            collector.beginLoop(7, true);
            try (var outer = collector.work("capture", true)) {
                nanos.set(110);
                try (var nested = collector.work("nested", true)) {
                    assertSame(RtTelemetry.Scope.NOOP, nested);
                    allocations.set(250);
                }
                nanos.set(150);
                allocations.set(260);
            }
            var renderer = new dev.comfyfluffy.caustica.renderer.runtime.RtTelemetryImpl();
            renderer.beginRenderFrame();
            renderer.endFrame();
            try (var finalize = collector.work("telemetry.finalize", true)) {
                nanos.set(170);
                allocations.set(300);
            }
            collector.endLoop(8, true);
            // Queued client tasks between runTick calls are retained without inventing a loop identity.
            try (var between = collector.work("debug.task", true)) { nanos.set(180); }
            collector.beginLoop(8, true);
            nanos.set(200);
            collector.endLoop(8, false);
            recording.stop();
            recording.dump(file);
        }
        var events = RecordingFile.readAllEvents(file);
        var work = named(events, "HostWork");
        assertEquals(List.of("capture", "telemetry.finalize", "debug.task"),
                work.stream().map(e -> e.getString("work")).toList());
        assertEquals(List.of(1L, 1L, 0L), work.stream().map(e -> e.getLong("loopId")).toList());
        assertEquals(List.of(60L, 40L, 0L), work.stream().map(e -> e.getLong("allocatedBytes")).toList());
        assertEquals(List.of(50L, 20L, 10L), work.stream().map(e -> e.getLong("elapsedNanos")).toList());
        assertTrue(work.stream().allMatch(e -> e.getLong("threadCpuNanos") == -1));
        var loops = named(events, "HostLoop");
        assertEquals(List.of(1L, 2L), loops.stream().map(e -> e.getLong("loopId")).toList());
        assertEquals(List.of(7L, 8L), loops.stream().map(e -> e.getLong("frameIdBefore")).toList());
        assertEquals(List.of(8L, 8L), loops.stream().map(e -> e.getLong("frameIdAfter")).toList());
        assertEquals(List.of(true, false), loops.stream().map(e -> e.getBoolean("rtActive")).toList());
    }

    @Test
    void disabledScopesReadNoClocksAndWorkerScopesAreExcluded() throws Exception {
        var calls = new AtomicLong();
        var collector = new MinecraftHostTelemetry.Collector(calls::incrementAndGet,
                calls::incrementAndGet, calls::incrementAndGet);
        collector.beginLoop(1, false);
        assertSame(RtTelemetry.Scope.NOOP, collector.work("disabled", false));
        assertSame(RtTelemetry.Scope.NOOP, collector.callback(MinecraftHostTelemetry.Callback.PARTICLES, false));
        assertSame(MinecraftHostTelemetry.SubmissionScope.NOOP, collector.submission(false));
        collector.endLoop(1, false);
        assertEquals(0, calls.get());
        collector.beginLoop(1, true);
        long before = calls.get();
        var result = new java.util.concurrent.atomic.AtomicReference<RtTelemetry.Scope>();
        var callbackResult = new java.util.concurrent.atomic.AtomicReference<RtTelemetry.Scope>();
        var submissionResult = new java.util.concurrent.atomic.AtomicReference<RtTelemetry.Scope>();
        Thread worker = new Thread(() -> {
            result.set(collector.work("worker", true));
            callbackResult.set(collector.callback(MinecraftHostTelemetry.Callback.PARTICLES, true));
            submissionResult.set(collector.submission(true));
        });
        worker.start();
        worker.join();
        assertSame(RtTelemetry.Scope.NOOP, result.get());
        assertSame(RtTelemetry.Scope.NOOP, callbackResult.get());
        assertSame(MinecraftHostTelemetry.SubmissionScope.NOOP, submissionResult.get());
        assertEquals(before, calls.get());
        collector.endLoop(1, true);
    }

    @Test
    void unavailableAndResetCountersRemainUnknown() {
        assertEquals(-1, MinecraftHostTelemetry.delta(-1, -1));
        assertEquals(-1, MinecraftHostTelemetry.delta(-1, 0));
        assertEquals(-1, MinecraftHostTelemetry.delta(12, -1));
        assertEquals(-1, MinecraftHostTelemetry.delta(12, 11));
        assertEquals(0, MinecraftHostTelemetry.delta(12, 12));
        assertEquals(4, MinecraftHostTelemetry.delta(12, 16));
    }

    private static List<RecordedEvent> named(List<RecordedEvent> events, String name) {
        return events.stream().filter(e -> e.getEventType().getName()
                .equals("dev.comfyfluffy.caustica." + name)).toList();
    }
}
