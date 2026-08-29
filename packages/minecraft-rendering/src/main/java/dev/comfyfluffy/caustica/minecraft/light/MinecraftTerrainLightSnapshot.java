package dev.comfyfluffy.caustica.minecraft.light;

import java.util.List;

/** Immutable terrain-light publication input keyed by a monotonic content generation. */
public record MinecraftTerrainLightSnapshot(List<MinecraftTerrainLightBatch> batches, long generation) {
    public MinecraftTerrainLightSnapshot {
        batches = List.copyOf(batches);
    }

    public static MinecraftTerrainLightSnapshot empty(long generation) {
        return new MinecraftTerrainLightSnapshot(List.of(), generation);
    }

    public boolean isEmpty() {
        return batches.isEmpty();
    }
}
