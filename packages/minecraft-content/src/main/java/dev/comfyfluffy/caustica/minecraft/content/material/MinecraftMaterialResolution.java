package dev.comfyfluffy.caustica.minecraft.content.material;

import dev.comfyfluffy.caustica.settings.ResourceId;

import java.util.Objects;

/** Narrow geometry/light result from the epoch material lookup. */
public record MinecraftMaterialResolution(int materialIndex, ResourceId material,
                                          MinecraftMaterialTopology topology,
                                          MinecraftMaterialEmission emission) {
    public MinecraftMaterialResolution {
        if (materialIndex < 0) throw new IllegalArgumentException("material index must be non-negative");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(emission, "emission");
    }
}
