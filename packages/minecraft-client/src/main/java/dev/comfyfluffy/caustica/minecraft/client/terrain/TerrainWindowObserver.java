package dev.comfyfluffy.caustica.minecraft.client.terrain;

import it.unimi.dsi.fastutil.longs.LongArrayList;

/** Render-thread scratch storage; each queued observation owns its column array. */
final class TerrainWindowObserver {
    @FunctionalInterface
    interface LoadedColumn { boolean contains(int x, int z); }

    private final LongArrayList available = new LongArrayList();

    TerrainWindow.Observation observe(long epoch, int cx, int cz, int radius, int minY, int maxY,
                                      LoadedColumn loaded) {
        available.clear();
        for (int x = cx - radius - 1; x <= cx + radius + 1; x++) {
            for (int z = cz - radius - 1; z <= cz + radius + 1; z++) {
                if (loaded.contains(x, z)) available.add(RtTerrain.columnKey(x, z));
            }
        }
        return new TerrainWindow.Observation(epoch, cx, cz, radius, minY, maxY, available.toLongArray());
    }
}
