package dev.comfyfluffy.caustica.minecraft.rendering.light;

import java.util.List;

/** One immutable terrain section's finite lights, ready for retained-channel publication. */
public record MinecraftTerrainLightBatch(long sectionKey, long revision,
                                         List<MinecraftTerrainEmitter> emitters) {
    public MinecraftTerrainLightBatch {
        emitters = List.copyOf(emitters);
    }
}
