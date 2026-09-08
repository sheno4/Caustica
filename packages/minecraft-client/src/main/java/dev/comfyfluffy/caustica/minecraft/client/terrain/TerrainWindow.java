package dev.comfyfluffy.caustica.minecraft.client.terrain;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** The coordination worker diffs immutable live-column observations into retained section requests. */
final class TerrainWindow {
    record Observation(long epoch, int x, int z, int radius, int minY, int maxY, long[] available) { }

    private LongOpenHashSet columns = new LongOpenHashSet();
    private LongOpenHashSet available = new LongOpenHashSet();
    private int minY, maxY;

    /** Source invalidations become visible before loaded-column eligibility or replacement tokens. */
    void apply(Observation next, LongConsumer want, LongConsumer remove, BiConsumer<Long, Boolean> column,
               Consumer<List<Long>> invalidationsQueued) {
        var nextAvailable = new LongOpenHashSet(next.available);
        var loaded = new LongOpenHashSet();
        for (long key : next.available) {
            if (Math.abs((int) (key >> 32) - next.x) <= next.radius
                    && Math.abs((int) key - next.z) <= next.radius) loaded.add(key);
        }
        var availability = new Long2BooleanOpenHashMap();
        for (long key : available) if (!nextAvailable.contains(key)) availability.put(key, false);
        for (long key : nextAvailable) if (!available.contains(key)) availability.put(key, true);
        var invalidated = new LongOpenHashSet();
        for (long key : availability.keySet()) {
            invalidateColumn(invalidated, key, Math.min(minY, next.minY) - 1, Math.max(maxY, next.maxY) + 1);
        }
        boolean heightChanged = minY != next.minY || maxY != next.maxY;
        var removed = new ArrayList<Long>();
        for (long key : columns) {
            if (!heightChanged && loaded.contains(key)) continue;
            for (int y = minY; y <= maxY; y++) removed.add(RtTerrain.sectionKey((int) (key >> 32), y, (int) key));
        }
        for (long key : removed) invalidated.add(key);
        invalidationsQueued.accept(new ArrayList<>(invalidated));
        for (var change : availability.long2BooleanEntrySet()) column.accept(change.getLongKey(), change.getBooleanValue());
        for (long key : removed) remove.accept(key);
        for (long key : loaded) {
            if (!heightChanged && columns.contains(key)) continue;
            for (int y = next.minY; y <= next.maxY; y++) want.accept(RtTerrain.sectionKey((int) (key >> 32), y, (int) key));
        }
        columns = loaded;
        available = nextAvailable;
        minY = next.minY;
        maxY = next.maxY;
    }

    void observe(long key, boolean present, BiConsumer<Long, Boolean> changed,
                 Consumer<List<Long>> invalidationsQueued) {
        if (!(present ? available.add(key) : available.remove(key))) return;
        var invalidated = new LongOpenHashSet();
        invalidateColumn(invalidated, key, minY - 1, maxY + 1);
        invalidationsQueued.accept(new ArrayList<>(invalidated));
        changed.accept(key, present);
    }

    private static void invalidateColumn(LongOpenHashSet keys, long column, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) keys.add(RtTerrain.sectionKey((int) (column >> 32), y, (int) column));
    }

    boolean neighborsLoaded(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!available.contains(RtTerrain.columnKey(x + dx, z + dz))) return false;
            }
        }
        return true;
    }

    int columns() { return columns.size(); }

    void clear() {
        columns.clear();
        available.clear();
        minY = maxY = 0;
    }
}
