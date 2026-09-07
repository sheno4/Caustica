package dev.comfyfluffy.caustica.renderer.raytracing.resource;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RtCompletionSlotPoolTest {
    @Test void completionCallbacksReturnReservationsAndLateReturnsRetireAfterClose() {
        var completions = new ArrayList<Runnable>();
        var retired = new ArrayList<Object>();
        var pool = new RtCompletionSlotPool<>(retired::add);
        var first = pool.acquire(slot -> true, Object::new, completions::add);
        var second = pool.acquire(slot -> true, Object::new, completions::add);
        assertNotSame(first, second);
        assertTrue(retired.isEmpty());
        completions.getFirst().run();
        assertSame(first, pool.acquire(slot -> true, Object::new, completions::add));
        pool.close();
        assertTrue(retired.isEmpty());
        completions.get(1).run();
        completions.get(2).run();
        assertEquals(List.of(second, first), retired);
        pool.close();
        assertEquals(2, retired.size());
    }

    @Test void rejectedCompletionRegistrationReturnsUnusedStorage() {
        var retired = new ArrayList<Object>();
        var pool = new RtCompletionSlotPool<>(retired::add);
        var resource = new Object();
        var rejection = new IllegalStateException("graphics use resolved");
        assertSame(rejection, assertThrows(IllegalStateException.class,
                () -> pool.acquire(slot -> true, () -> resource, callback -> { throw rejection; })));
        var completions = new ArrayList<Runnable>();
        assertSame(resource, pool.acquire(slot -> true, () -> fail("must reuse returned storage"), completions::add));
        completions.getFirst().run();
        pool.close();
        assertEquals(List.of(resource), retired);
    }

    @Test
    void onlyCompletedSlotsCanBeReused() {
        List<Object> retired = new ArrayList<>();
        var pool = new RtCompletionSlotPool<>(retired::add);
        Object first = pool.acquire(slot -> true, Object::new);
        Object second = pool.acquire(slot -> true, Object::new);
        assertNotSame(first, second);
        pool.release(first);
        assertSame(first, pool.acquire(slot -> true, Object::new));
        assertTrue(retired.isEmpty());
        pool.release(first);
        pool.release(second);
        pool.close();
        assertEquals(List.of(first, second), retired);
    }

    @Test
    void growthReplacesCompletedStorageAndPreservesInFlightStorage() {
        List<Integer> retired = new ArrayList<>();
        var pool = new RtCompletionSlotPool<Integer>(retired::add);
        Integer inFlight = pool.acquire(size -> size >= 256, () -> 256);
        Integer completed = pool.acquire(size -> size >= 256, () -> 256);
        pool.release(completed);
        assertEquals(512, pool.acquire(size -> size >= 300, () -> 512));
        assertEquals(List.of(completed), retired);
        pool.release(inFlight);
        assertEquals(256, pool.acquire(size -> size >= 128, () -> fail("must reuse completed slot")));
    }

    @Test
    void closingRetiresAvailableSlotsAndLateCompletionsExactlyOnce() {
        List<Object> retired = new ArrayList<>();
        var pool = new RtCompletionSlotPool<>(retired::add);
        Object first = pool.acquire(slot -> true, Object::new);
        Object second = pool.acquire(slot -> true, Object::new);
        pool.release(first);
        pool.close();
        pool.close();
        assertEquals(List.of(first), retired);
        pool.release(second);
        assertEquals(List.of(first, second), retired);
        assertThrows(IllegalStateException.class, () -> pool.acquire(slot -> true, Object::new));
    }

    @Test
    void capacityHasGrowthRoomAndSupportsLargestTableByteCount() {
        assertEquals(256L, RtCompletionSlotPool.capacity(0));
        assertEquals(256L, RtCompletionSlotPool.capacity(256));
        assertEquals(512L, RtCompletionSlotPool.capacity(257));
        assertEquals(1L << 31, RtCompletionSlotPool.capacity(Integer.MAX_VALUE));
    }
}
