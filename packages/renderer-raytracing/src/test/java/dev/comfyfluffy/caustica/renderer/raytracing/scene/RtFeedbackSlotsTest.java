package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class RtFeedbackSlotsTest {
    @Test
    void metadataReleaseFailureDestroysTheSlotInsteadOfReusingIt() {
        var failure = new IllegalStateException("metadata release");
        var destroyed = new ArrayList<Integer>();
        try (var pool = new RtFeedbackSlots<Integer>(ignored -> { throw failure; }, destroyed::add)) {
            var slot = pool.acquire(ignored -> true, () -> 1);
            assertSame(failure, assertThrows(IllegalStateException.class, slot::close));
            assertEquals(java.util.List.of(1), destroyed);
            var next = pool.acquire(ignored -> true, () -> 2);
            assertEquals(2, next.get());
            assertSame(failure, assertThrows(IllegalStateException.class, next::close));
        }
        assertEquals(java.util.List.of(1, 2), destroyed);
    }

    @Test
    void failedSlotDestructionDoesNotSkipOtherCompletedSlots() {
        var failure = new IllegalStateException("slot destruction");
        var destroyed = new ArrayList<Integer>();
        var pool = new RtFeedbackSlots<Integer>(ignored -> { }, value -> {
            destroyed.add(value);
            throw failure;
        });
        var first = pool.acquire(ignored -> true, () -> 1);
        var second = pool.acquire(ignored -> true, () -> 2);
        first.close();
        second.close();
        assertSame(failure, assertThrows(IllegalStateException.class, pool::close));
        assertEquals(java.util.List.of(1, 2), destroyed);
        assertDoesNotThrow(pool::close);
    }

    @Test
    void failedHistoryReleaseStillClearsTheClaimAndRejectsLateSubmission() {
        var history = new RtFeedbackHistory<String>();
        var failure = new IllegalStateException("history release");
        var previous = SharedResource.owned("old", ignored -> { throw failure; });
        history.submitted(previous);
        previous.close();

        assertSame(failure, assertThrows(IllegalStateException.class, history::close));
        assertNull(history.capture());
        try (var late = SharedResource.owned("late", ignored -> { })) {
            history.submitted(late);
            assertNull(history.capture());
        }
        assertDoesNotThrow(history::close);
    }

    @Test
    void submittedReadersAndViewHistoryPreventReuseUntilTheirLastRelease() {
        var sequence = new AtomicInteger();
        var recycled = new ArrayList<Integer>();
        try (var pool = new RtFeedbackSlots<Integer>(recycled::add, ignored -> { })) {
            var first = pool.acquire(ignored -> true, sequence::incrementAndGet);
            var gpuReader = first.retain();
            var history = first.retain();
            first.close();
            var second = pool.acquire(ignored -> true, sequence::incrementAndGet);
            assertEquals(2, second.get());
            gpuReader.close();
            assertTrue(recycled.isEmpty());
            history.close();
            assertEquals(java.util.List.of(1), recycled);
            try (var reused = pool.acquire(ignored -> true, sequence::incrementAndGet)) {
                assertEquals(1, reused.get());
            }
            second.close();
        }
    }

    @Test
    void abandonedUnsubmittedTargetReturnsIndependentlyOfPreviousHistory() {
        var sequence = new AtomicInteger();
        try (var pool = new RtFeedbackSlots<Integer>(ignored -> { }, ignored -> { });
             var history = new RtFeedbackHistory<Integer>()) {
            var first = pool.acquire(ignored -> true, sequence::incrementAndGet);
            history.submitted(first);
            first.close();
            var abandoned = pool.acquire(ignored -> true, sequence::incrementAndGet);
            abandoned.close();
            try (var prior = history.capture(); var next = pool.acquire(ignored -> true, sequence::incrementAndGet)) {
                assertEquals(1, prior.get());
                assertEquals(2, next.get());
            }
        }
    }

    @Test
    void retiredPoolDefersDestructionUntilReadersReleaseAndRejectsNewAcquisition() {
        var destroyed = new ArrayList<Integer>();
        var pool = new RtFeedbackSlots<Integer>(ignored -> { }, destroyed::add);
        var frame = pool.acquire(ignored -> true, () -> 7);
        var reader = frame.retain();
        pool.close();
        frame.close();
        assertTrue(destroyed.isEmpty());
        reader.close();
        assertEquals(java.util.List.of(7), destroyed);
        assertThrows(IllegalStateException.class, () -> pool.acquire(ignored -> true, () -> 9));
    }

    @Test
    void incompatibleCompletedStorageIsRetiredBeforeReplacement() {
        var destroyed = new ArrayList<Integer>();
        try (var pool = new RtFeedbackSlots<Integer>(ignored -> { }, destroyed::add)) {
            pool.acquire(ignored -> true, () -> 16).close();
            try (var large = pool.acquire(capacity -> capacity >= 32, () -> 32)) {
                assertEquals(32, large.get());
                assertEquals(java.util.List.of(16), destroyed);
            }
        }
    }

    @Test
    void historiesAreIndependentAndOnlySubmittedRevisionsBecomePredecessors() {
        var released = new AtomicInteger();
        try (var firstView = new RtFeedbackHistory<String>(); var secondView = new RtFeedbackHistory<String>();
             var first = SharedResource.owned("A", ignored -> released.incrementAndGet());
             var prepared = SharedResource.owned("B", ignored -> released.incrementAndGet());
             var current = SharedResource.owned("C", ignored -> released.incrementAndGet())) {
            firstView.submitted(first);
            secondView.submitted(prepared);
            try (var before = firstView.capture()) { assertEquals("A", before.get()); }
            firstView.submitted(current);
            firstView.submitted(current);
            try (var one = firstView.capture(); var two = secondView.capture()) {
                assertEquals("C", one.get());
                assertEquals("B", two.get());
            }
            firstView.close();
            firstView.submitted(prepared);
            assertNull(firstView.capture());
            assertEquals(0, released.get());
        }
        assertEquals(3, released.get());
    }
}
