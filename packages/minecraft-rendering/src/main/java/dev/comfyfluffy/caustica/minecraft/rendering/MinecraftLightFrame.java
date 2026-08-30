package dev.comfyfluffy.caustica.minecraft.rendering;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainLightSnapshot;

import java.util.Objects;
import java.util.Optional;

/** Immutable light inputs captured for one Minecraft frame. */
public record MinecraftLightFrame(Optional<MinecraftCelestialFrame> celestial,
                                  Optional<LightDescriptor.Spot> helmet,
                                  MinecraftTerrainLightSnapshot terrainLights) {
    public MinecraftLightFrame {
        Objects.requireNonNull(celestial, "celestial");
        Objects.requireNonNull(helmet, "helmet");
        Objects.requireNonNull(terrainLights, "terrainLights");
    }
}
