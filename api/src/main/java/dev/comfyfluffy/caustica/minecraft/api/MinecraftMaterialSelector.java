package dev.comfyfluffy.caustica.minecraft.api;

import dev.comfyfluffy.caustica.api.ResourceId;

/**
 * Finite Minecraft material case declaration. A null component is a wildcard. A concrete geometry adds
 * that geometry case for every matching texture-derived material resource before the epoch catalog is
 * compiled. Selectors do not run against the five fixed host definitions.
 */
public record MinecraftMaterialSelector(ResourceId material, ResourceId geometry) {
    public boolean matches(ResourceId candidateMaterial, ResourceId candidateGeometry) {
        return (material == null || material.equals(candidateMaterial))
                && (geometry == null || geometry.equals(candidateGeometry));
    }
}
