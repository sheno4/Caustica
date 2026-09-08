package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.renderer.presentation.gen.ExposureStateData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RtExposureReadbacksTest {
    @Test
    void pendingSlotsCannotBeReadOrReusedAndExhaustionSkipsOptionalFeedback() {
        var first = new Buffer();
        var second = new Buffer();
        try (var pool = pool(first, second)) {
            var a = pool.acquire();
            var b = pool.acquire();
            assertNotSame(a.buffer(), b.buffer());
            assertNull(pool.acquire());
            assertNull(pool.latest(1));
            a.submitted(10, .5f, 1);
            assertNull(pool.latest(1));
            assertEquals(0, first.reads + second.reads);

            Buffer completed = a.buffer();
            completed.state = state(.25f);
            a.close();
            assertSame(completed.state, pool.latest(1).state());
            assertEquals(1, completed.reads);
            var writable = pool.acquire();
            assertSame(completed, writable.buffer());
            assertNotSame(b.buffer(), writable.buffer());
            writable.close();
            b.close();
        }
        assertEquals(1, first.destroys);
        assertEquals(1, second.destroys);
    }

    @Test
    void newestSubmittedSerialWinsWhenCompletionsArriveOutOfOrder() {
        try (var pool = pool(new Buffer(), new Buffer())) {
            var old = pool.acquire();
            var recent = pool.acquire();
            old.buffer().state = state(.5f);
            recent.buffer().state = state(.125f);
            old.submitted(10, 1, 1);
            recent.submitted(11, .5f, 1);

            recent.close();
            var newest = pool.latest(1);
            assertEquals(11, newest.frameId());
            assertEquals(.5f, newest.preExposure());
            assertEquals(.125f, newest.state().previous());
            old.close();
            assertSame(newest, pool.latest(1));
        }
    }

    @Test
    void abandoningAnUnsubmittedCopyReturnsItsSlotWithoutReadingOrPublishing() {
        var buffer = new Buffer();
        try (var pool = pool(buffer)) {
            var abandoned = pool.acquire();
            abandoned.close();
            abandoned.close();
            assertEquals(0, buffer.reads);
            assertNull(pool.latest(0));
            var retry = pool.acquire();
            assertSame(buffer, retry.buffer());
            buffer.state = state(.125f);
            retry.submitted(20, 1, 0);
            retry.close();
            assertEquals(1, buffer.reads);
            assertEquals(20, pool.latest(0).frameId());
        }
        assertEquals(1, buffer.destroys);
    }

    @Test
    void resetRejectsPriorEpochFeedbackIncludingLateCompletions() {
        try (var pool = pool(new Buffer(), new Buffer(), new Buffer())) {
            var oldCompleted = pool.acquire();
            var oldPending = pool.acquire();
            var current = pool.acquire();
            oldCompleted.buffer().state = state(.5f);
            oldPending.buffer().state = state(.25f);
            current.buffer().state = state(.125f);
            oldCompleted.submitted(1, 1, 1);
            oldPending.submitted(2, .5f, 1);
            current.submitted(3, 1, 2);
            oldCompleted.close();
            assertNotNull(pool.latest(1));
            assertNull(pool.latest(2));
            current.close();
            var newest = pool.latest(2);
            oldPending.close();
            assertSame(newest, pool.latest(2));
            assertNull(pool.latest(1));
        }
    }

    @Test
    void shutdownKeepsPendingBufferAliveUntilItsCompletionRelease() {
        var first = new Buffer();
        var second = new Buffer();
        var pool = pool(first, second);
        var pending = pool.acquire();
        Buffer inFlight = pending.buffer();
        inFlight.state = state(.5f);
        pending.submitted(1, 1, 0);

        pool.close();
        assertEquals(0, inFlight.destroys);
        assertEquals(1, first.destroys + second.destroys);
        pending.close();
        assertEquals(1, inFlight.destroys);
        assertEquals(2, first.destroys + second.destroys);
        assertNull(pool.latest(0));
        pool.close();
        assertEquals(2, first.destroys + second.destroys);
    }

    private static RtExposureReadbacks<Buffer> pool(Buffer... buffers) {
        return new RtExposureReadbacks<>(List.of(buffers), buffer -> {
            assertEquals(0, buffer.destroys);
            buffer.reads++;
            return buffer.state;
        }, buffer -> buffer.destroys++);
    }

    private static ExposureStateData state(float exposure) {
        return new ExposureStateData(exposure, 1, 0, 0, 0, 0, 0, 0,
                1, 0, 0, 0, 1, 0);
    }

    private static final class Buffer {
        ExposureStateData state;
        int reads;
        int destroys;
    }
}
