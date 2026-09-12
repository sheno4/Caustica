package dev.comfyfluffy.caustica.minecraft.client.terrain;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TerrainWindowObserverTest {
    @Test
    void stationaryCameraStillObservesLoadsAndUnloadsWithoutMutatingQueuedArrays() {
        var observer = new TerrainWindowObserver();
        Set<Long> loaded = new HashSet<>();
        loaded.add(RtTerrain.columnKey(-2, 0));
        loaded.add(RtTerrain.columnKey(0, 0));
        var first = observer.observe(7, 0, 0, 1, -4, 19, (x, z) -> loaded.contains(RtTerrain.columnKey(x, z)));
        loaded.remove(RtTerrain.columnKey(-2, 0));
        loaded.add(RtTerrain.columnKey(2, 2));
        var next = observer.observe(7, 0, 0, 1, -4, 19, (x, z) -> loaded.contains(RtTerrain.columnKey(x, z)));
        assertArrayEquals(new long[]{RtTerrain.columnKey(-2, 0), RtTerrain.columnKey(0, 0)}, first.available());
        assertArrayEquals(new long[]{RtTerrain.columnKey(0, 0), RtTerrain.columnKey(2, 2)}, next.available());
        assertNotSame(first.available(), next.available());
        assertEquals(7, next.epoch());
        assertEquals(-4, next.minY());
        assertEquals(19, next.maxY());
    }

    @Test
    void growthAndSmallerWindowsKeepTheOneColumnBorderAndSnapshotOwnership() {
        var observer = new TerrainWindowObserver();
        var first = observer.observe(1, -5, 9, 1, 0, 2, (x, z) -> true);
        var larger = observer.observe(2, 10, -10, 4, -1, 3, (x, z) -> true);
        var empty = observer.observe(3, 10, -10, 1, -1, 3, (x, z) -> false);
        assertEquals(25, first.available().length);
        assertEquals(RtTerrain.columnKey(-7, 7), first.available()[0]);
        assertEquals(RtTerrain.columnKey(-3, 11), first.available()[24]);
        assertEquals(121, larger.available().length);
        assertEquals(RtTerrain.columnKey(5, -15), larger.available()[0]);
        assertEquals(RtTerrain.columnKey(15, -5), larger.available()[120]);
        assertEquals(0, empty.available().length);
    }
}
