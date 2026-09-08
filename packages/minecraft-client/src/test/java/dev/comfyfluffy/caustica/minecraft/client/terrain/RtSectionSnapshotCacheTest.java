package dev.comfyfluffy.caustica.minecraft.client.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class RtSectionSnapshotCacheTest {
    @Test
    void reusesPaletteOnlyForTheExactLiveColumn() {
        var cache = new RtSectionSnapshots.Cache(2);
        var originalColumn = new Object();
        var originalPalette = new Object();
        cache.put(1, originalColumn, originalPalette);
        assertSame(originalPalette, cache.get(1, originalColumn));

        var replacementColumn = new Object();
        assertNull(cache.get(1, replacementColumn));
        var replacementPalette = new Object();
        cache.put(1, replacementColumn, replacementPalette);
        assertSame(replacementPalette, cache.get(1, replacementColumn));
        assertNull(cache.get(1, originalColumn));
    }

    @Test
    void invalidationAndEvictionDoNotAffectPalettesAlreadyRetainedByRegions() {
        var cache = new RtSectionSnapshots.Cache(2);
        var column = new Object();
        var retainedPalette = new Object();
        cache.put(1, column, retainedPalette);
        var regionPalette = cache.get(1, column);
        cache.put(2, column, new Object());
        assertSame(retainedPalette, cache.get(1, column));
        cache.put(3, column, new Object());
        assertNull(cache.get(2, column));
        assertSame(retainedPalette, cache.get(1, column));
        cache.invalidate(1);
        assertNull(cache.get(1, column));
        assertSame(retainedPalette, regionPalette);
        cache.clear();
        assertNull(cache.get(3, column));
    }
}
