package dev.comfyfluffy.caustica.renderer.raytracing.accel;

import dev.comfyfluffy.caustica.renderer.raytracing.resource.RtCompletionSlotPool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TlasBuilderCapacityTest {
    @Test void capacityProvidesHeadroomForSceneGrowthAndAddressableEmptyInput() {
        assertEquals(64, TlasBuilder.capacity(0));
        assertEquals(64, TlasBuilder.capacity(64));
        assertEquals(128, TlasBuilder.capacity(65));
        assertEquals(65536, TlasBuilder.capacity(46658));
        assertEquals(32768, TlasBuilder.capacity(27355));
        assertEquals(Integer.MAX_VALUE, TlasBuilder.capacity(Integer.MAX_VALUE));
    }

    @Test void completedHeadroomSurvivesGrowthAndShrinkWhileLargerReservationsReplaceCompletedStorage() {
        var retired = new ArrayList<Storage>();
        var callbacks = new ArrayList<Runnable>();
        var pool = new RtCompletionSlotPool<Storage>(retired::add);
        var first = reserve(pool, callbacks, 100);
        assertEquals(128, first.capacity);
        var inFlight = reserve(pool, callbacks, 100);
        assertNotSame(first, inFlight);
        callbacks.getFirst().run();
        assertSame(first, reserve(pool, callbacks, 120));
        callbacks.get(2).run();
        assertSame(first, reserve(pool, callbacks, 0));
        callbacks.get(3).run();
        var grown = reserve(pool, callbacks, 129);
        assertEquals(256, grown.capacity);
        assertEquals(List.of(first), retired);
        callbacks.get(1).run();
        assertSame(inFlight, reserve(pool, callbacks, 64));
        pool.close();
        assertEquals(List.of(first), retired);
        callbacks.get(4).run();
        callbacks.get(5).run();
        assertEquals(List.of(first, grown, inFlight), retired);
    }

    private static Storage reserve(RtCompletionSlotPool<Storage> pool, List<Runnable> callbacks, int count) {
        return pool.acquire(slot -> slot.capacity >= count,
                () -> new Storage(TlasBuilder.capacity(count)), callbacks::add);
    }

    private static final class Storage {
        final int capacity;
        Storage(int capacity) { this.capacity = capacity; }
    }
}
