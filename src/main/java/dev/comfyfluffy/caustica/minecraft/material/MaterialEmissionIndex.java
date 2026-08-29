package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.settings.ResourceId;

import java.util.Map;

/** Resource-epoch proof that a material is used by at least one emitting geometry source. */
record MaterialEmissionIndex(Map<ResourceId, Integer> maxEmission, int emittingGeometry, int failures) {
    MaterialEmissionIndex {
        maxEmission = Map.copyOf(maxEmission);
        if (emittingGeometry < 0 || failures < 0) {
            throw new IllegalArgumentException("emission analysis counters must be non-negative");
        }
    }

    boolean permits(ResourceId material) {
        return maxEmission.containsKey(material);
    }

    int maxEmission(ResourceId material) {
        return maxEmission.getOrDefault(material, 0);
    }
}
