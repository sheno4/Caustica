package dev.comfyfluffy.caustica.minecraft.client;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class VanillaTerrainSuspensionTest {
    @Test
    void graphSettlesOnceAndVanillaRebuildsOnceWhenReplacementEnds() {
        var terrain = new VanillaTerrainSuspension();
        var settles = new AtomicInteger();
        var rebuilds = new AtomicInteger();
        terrain.resume(false, rebuilds::incrementAndGet);
        for (int frame = 0; frame < 100; frame++) {
            terrain.suspend(settles::incrementAndGet);
            terrain.resume(true, rebuilds::incrementAndGet);
        }
        assertTrue(terrain.suspended());
        assertEquals(1, settles.get());
        assertEquals(0, rebuilds.get());
        terrain.resume(false, () -> {
            assertTrue(terrain.suspended());
            rebuilds.incrementAndGet();
        });
        terrain.resume(false, rebuilds::incrementAndGet);
        assertFalse(terrain.suspended());
        assertEquals(1, rebuilds.get());
        terrain.suspend(settles::incrementAndGet);
        assertEquals(2, settles.get());
    }

    @Test
    void worldReplacementResetsTheSuspensionWithoutRebuildingTheOldWorld() {
        var terrain = new VanillaTerrainSuspension();
        terrain.suspend(() -> { });
        terrain.reset();
        assertFalse(terrain.suspended());
        terrain.resume(false, () -> fail("new world initializes its own terrain"));
    }

    @Test
    void failedReconstructionDoesNotClaimThatVanillaTerrainIsReady() {
        var terrain = new VanillaTerrainSuspension();
        terrain.suspend(() -> { });
        var expected = new IllegalStateException("rebuild failed");
        assertSame(expected, assertThrows(IllegalStateException.class,
                () -> terrain.resume(false, () -> { throw expected; })));
        assertTrue(terrain.suspended());
        terrain.resume(false, () -> { });
        assertFalse(terrain.suspended());
    }

    @Test
    void membershipConsumesRotatingDeltaBuffersAndRemovalsWinWithinOneDelta() {
        var loaded = new LongOpenHashSet(new long[] {1, 2});
        var empty = new LongOpenHashSet(new long[] {10, 20});
        var addedLoaded = new LongOpenHashSet(new long[] {2, 3});
        var removedLoaded = new LongOpenHashSet(new long[] {1, 2});
        var addedEmpty = new LongOpenHashSet(new long[] {20, 30});
        var removedEmpty = new LongOpenHashSet(new long[] {10, 20});
        VanillaTerrainSuspension.applyDelta(loaded, addedLoaded, removedLoaded);
        VanillaTerrainSuspension.applyDelta(empty, addedEmpty, removedEmpty);
        addedLoaded.clear();
        removedLoaded.clear();
        addedEmpty.clear();
        removedEmpty.clear();
        assertEquals(new LongOpenHashSet(new long[] {3}), loaded);
        assertEquals(new LongOpenHashSet(new long[] {30}), empty);
        addedLoaded.add(1);
        addedEmpty.add(10);
        VanillaTerrainSuspension.applyDelta(loaded, addedLoaded, removedLoaded);
        VanillaTerrainSuspension.applyDelta(empty, addedEmpty, removedEmpty);
        assertEquals(new LongOpenHashSet(new long[] {1, 3}), loaded);
        assertEquals(new LongOpenHashSet(new long[] {10, 30}), empty);
    }

    @Test
    void longFlightRetainsMembershipInsteadOfAccumulatingHistoricalMessages() {
        var loaded = new LongOpenHashSet();
        var empty = new LongOpenHashSet();
        var none = new LongOpenHashSet();
        for (long column = 0; column < 10_000; column++) {
            var chunk = new LongOpenHashSet(new long[] {column});
            var sections = new LongOpenHashSet(new long[] {column * 2, column * 2 + 1});
            VanillaTerrainSuspension.applyDelta(loaded, chunk, none);
            VanillaTerrainSuspension.applyDelta(empty, sections, none);
            VanillaTerrainSuspension.applyDelta(loaded, none, chunk);
            VanillaTerrainSuspension.applyDelta(empty, none, sections);
        }
        assertTrue(loaded.isEmpty());
        assertTrue(empty.isEmpty());
    }
}
