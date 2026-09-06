package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RtTraceSlotPoolTest {
    @Test
    void onlyCompletedSlotsCanBeReused() {
        List<Object> retired = new ArrayList<>();
        var pool = new RtTraceSlotPool<>(retired::add);
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
        var pool = new RtTraceSlotPool<Integer>(retired::add);
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
        var pool = new RtTraceSlotPool<>(retired::add);
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
        assertEquals(256L, RtTraceSlotPool.capacity(0));
        assertEquals(256L, RtTraceSlotPool.capacity(256));
        assertEquals(512L, RtTraceSlotPool.capacity(257));
        assertEquals(1L << 31, RtTraceSlotPool.capacity(Integer.MAX_VALUE));
    }
}
