package dev.comfyfluffy.caustica.minecraft.rendering.light;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;

import java.util.List;

/** One immutable terrain section's finite lights, ready for retained-channel publication. */
public record MinecraftTerrainLightBatch(long sectionKey, long revision,
                                         List<LightDescriptor.Finite> lights) {
    public MinecraftTerrainLightBatch {
        lights = List.copyOf(lights);
    }
}
