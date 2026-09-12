package dev.comfyfluffy.caustica.engine.vulkan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class GpuDiagnosticCheckpointsTest {
    @Test void scopeRecordsDistinctStartAndEndTokensBeforeClosingHostScope() {
        var history = new GpuDiagnosticCheckpoints.Ring(4);
        var events = new ArrayList<String>();
        var tokens = new ArrayList<Long>();
        var scope = GpuDiagnosticCheckpoints.wrap(history, 0x1234, "BLAS build", token -> {
            tokens.add(token);
            var entry = history.resolve(token);
            assertEquals(0x1234, entry.command());
            assertEquals("BLAS build", entry.label());
            events.add(entry.boundary().name());
        }, () -> events.add("host close"));
        assertEquals(java.util.List.of("START"), events);
        scope.close();
        assertEquals(java.util.List.of("START", "END", "host close"), events);
        assertNotEquals(0L, tokens.getFirst());
        assertNotEquals(tokens.getFirst(), tokens.getLast());
    }

    @Test void boundedHistoryRejectsExpiredAndUnknownTokens() {
        var history = new GpuDiagnosticCheckpoints.Ring(2);
        long first = history.add(1, "first", GpuDiagnosticCheckpoints.Boundary.START);
        long second = history.add(2, "second", GpuDiagnosticCheckpoints.Boundary.END);
        long third = history.add(3, "third", GpuDiagnosticCheckpoints.Boundary.START);
        assertNull(history.resolve(first));
        assertNull(history.resolve(0));
        assertNull(history.resolve(Long.MAX_VALUE));
        assertEquals("second", history.resolve(second).label());
        assertEquals("third", history.resolve(third).label());
    }

    @Test void neighborhoodPreservesParentContextAndExcludesOtherCommands() {
        var history = new GpuDiagnosticCheckpoints.Ring(4);
        long expired = history.add(1, "expired", GpuDiagnosticCheckpoints.Boundary.START);
        long parent = history.add(1, "fill stable planes", GpuDiagnosticCheckpoints.Boundary.START);
        history.add(2, "BLAS build", GpuDiagnosticCheckpoints.Boundary.START);
        long trace = history.add(1, "trace rays", GpuDiagnosticCheckpoints.Boundary.START);
        long end = history.add(1, "trace rays", GpuDiagnosticCheckpoints.Boundary.END);
        assertEquals(java.util.List.of(parent, trace, end),
                history.neighborhood(trace).stream().map(GpuDiagnosticCheckpoints.Entry::token).toList());
        assertTrue(history.neighborhood(expired).isEmpty());
        assertTrue(history.neighborhood(0).isEmpty());
    }
}
