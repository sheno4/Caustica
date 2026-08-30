package dev.comfyfluffy.caustica.minecraft.content.material;

import dev.comfyfluffy.caustica.settings.ResourceId;

import java.util.Map;

/** Resource-epoch proof that a material is used by at least one emitting geometry source. */
public record MaterialEmissionIndex(Map<ResourceId, Integer> maxEmission, int emittingGeometry, int failures) {
    public MaterialEmissionIndex {
        maxEmission = Map.copyOf(maxEmission);
        if (emittingGeometry < 0 || failures < 0) {
            throw new IllegalArgumentException("emission analysis counters must be non-negative");
        }
    }

    public boolean permits(ResourceId material) {
        return maxEmission.containsKey(material);
    }

    public int maxEmission(ResourceId material) {
        return maxEmission.getOrDefault(material, 0);
    }
}
