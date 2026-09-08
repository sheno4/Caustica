package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class RtSubmittedRevisionTest {
    @Test
    void repeatedSubmissionKeepsTheSameRevisionAliveUntilHistoryAndFrameRetire() {
        var a = new Revision();
        var history = new RtSubmittedRevision<Revision>();
        var producer = a.owner();
        history.submitted(producer);
        producer.close();

        var frame = history.acquire();
        assertSame(a, frame.get());
        history.submitted(frame);
        assertEquals(0, a.releases);
        history.close();
        assertEquals(0, a.releases);
        assertSame(a, frame.get());
        frame.close();
        assertEquals(1, a.releases);
    }

    @Test
    void preparedButSkippedRevisionNeverBecomesTheSubmittedPredecessor() {
        var a = new Revision();
        var b = new Revision();
        var c = new Revision();
        var history = new RtSubmittedRevision<Revision>();
        try (var producer = a.owner()) { history.submitted(producer); }
        b.owner().close();
        assertEquals(1, b.releases);

        try (var predecessor = history.acquire(); var current = c.owner()) {
            assertSame(a, predecessor.get());
            history.submitted(current);
            assertEquals(0, a.releases);
            try (var nextPredecessor = history.acquire()) {
                assertSame(c, nextPredecessor.get());
            }
        }
        assertEquals(1, a.releases);
        assertEquals(0, c.releases);
        history.close();
        assertEquals(1, c.releases);
    }

    @Test
    void abandoningAFrameLeavesTheLastSubmittedRevisionAsThePredecessor() {
        var a = new Revision();
        var b = new Revision();
        var c = new Revision();
        var history = new RtSubmittedRevision<Revision>();
        try (var producer = a.owner()) { history.submitted(producer); }

        try (var abandonedPredecessor = history.acquire(); var abandonedCurrent = b.owner()) {
            assertSame(a, abandonedPredecessor.get());
            assertSame(b, abandonedCurrent.get());
        }
        assertEquals(1, b.releases);
        assertEquals(0, a.releases);
        try (var predecessor = history.acquire(); var current = c.owner()) {
            assertSame(a, predecessor.get());
            history.submitted(current);
        }
        assertEquals(1, a.releases);
        try (var repeatedCurrent = history.acquire()) {
            assertSame(c, repeatedCurrent.get());
            history.submitted(repeatedCurrent);
        }
        assertEquals(0, c.releases);
        history.close();
        assertEquals(1, c.releases);
    }

    @Test
    void twoViewsRetainTheirOwnActualSubmittedPredecessors() {
        var a = new Revision();
        var b = new Revision();
        var c = new Revision();
        var firstView = new RtSubmittedRevision<Revision>();
        var secondView = new RtSubmittedRevision<Revision>();
        try (var producer = a.owner()) {
            firstView.submitted(producer);
            secondView.submitted(producer);
        }
        try (var producer = b.owner()) { firstView.submitted(producer); }
        try (var first = firstView.acquire(); var second = secondView.acquire()) {
            assertSame(b, first.get());
            assertSame(a, second.get());
        }
        firstView.close();
        assertEquals(1, b.releases);
        assertEquals(0, a.releases);

        try (var producer = c.owner()) { secondView.submitted(producer); }
        assertEquals(1, a.releases);
        try (var second = secondView.acquire()) { assertSame(c, second.get()); }
        secondView.close();
        assertEquals(1, c.releases);
    }

    @Test
    void resetDropsHistoryWhileAnInFlightFrameOwnsItsPredecessor() {
        var a = new Revision();
        var b = new Revision();
        var history = new RtSubmittedRevision<Revision>();
        assertNull(history.acquire());
        try (var producer = a.owner()) { history.submitted(producer); }
        var inFlight = history.acquire();

        history.close();
        history.close();
        assertNull(history.acquire());
        assertEquals(0, a.releases);
        try (var producer = b.owner()) { history.submitted(producer); }
        assertSame(a, inFlight.get());
        inFlight.close();
        assertEquals(1, a.releases);
        assertEquals(0, b.releases);
        history.close();
        assertEquals(1, b.releases);
    }

    private static final class Revision {
        int releases;

        SharedResource<Revision> owner() {
            return SharedResource.owned(this, value -> value.releases++);
        }
    }
}
