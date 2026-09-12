package dev.comfyfluffy.caustica.engine.vulkan;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.junit.jupiter.api.Assertions.*;

final class GpuCrashHistoryTest {
    @Test
    void wrapKeepsNewestEntriesInOrder() {
        var history = new GpuCrashHistory(3);
        for (long i = 0; i < 5; i++) history.add(GpuCrashHistory.Event.POOL_RESET, i, i + 10, i + 20, i + 30);
        var entries = history.freeze();
        assertEquals(3, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            assertEquals(i + 2, entry.sequence());
            assertEquals(i + 2, entry.handle());
            assertEquals(i + 12, entry.target());
            assertEquals(i + 22, entry.observed());
            assertEquals(i + 32, entry.detail());
        }
    }

    @Test
    void failureSnapshotSurvivesCleanupEvents() {
        var history = new GpuCrashHistory(2);
        history.add(GpuCrashHistory.Event.COMPUTE_SUBMIT, 7, 13, 12, -4);
        var captured = history.freeze();
        history.add(GpuCrashHistory.Event.POOL_DESTROY, 8, 0, 0, 0);
        assertEquals(captured, history.freeze());
        assertThrows(UnsupportedOperationException.class, () -> captured.clear());
    }

    @Test
    void concurrentWritersPublishCompleteEntries() throws Exception {
        var history = new GpuCrashHistory(1024);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var work = new ArrayList<Future<?>>();
            for (int writer = 0; writer < 4; writer++) {
                int base = writer * 128;
                work.add(workers.submit(() -> {
                    for (long i = base; i < base + 128; i++) {
                        history.add(GpuCrashHistory.Event.GRAPHICS_RETIRE, i, i + 1, i + 2, i + 3);
                    }
                }));
            }
            for (var task : work) task.get();
        }
        var entries = history.freeze();
        assertEquals(512, entries.size());
        assertEquals(512, entries.stream().map(GpuCrashHistory.Entry::handle).distinct().count());
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            assertEquals(i, entry.sequence());
            assertEquals(entry.handle() + 1, entry.target());
            assertEquals(entry.handle() + 2, entry.observed());
            assertEquals(entry.handle() + 3, entry.detail());
        }
    }
}
