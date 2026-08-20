package dev.comfyfluffy.caustica.engine.material;

import dev.comfyfluffy.caustica.api.provider.MaterialTextureResource;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureKind;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Renderer-owned, deterministically ordered aggregation of material texture resources for one resource epoch.
 */
public record MaterialCatalog(List<MaterialTextureResource> atlasResources,
                              List<MaterialTextureResource> standaloneResources) {
    public MaterialCatalog {
        atlasResources = sorted(atlasResources, MaterialTextureKind.SHARED_ATLAS);
        standaloneResources = sorted(standaloneResources, MaterialTextureKind.STANDALONE);
        Set<ResourceId> ids = new HashSet<>();
        for (MaterialTextureResource resource : atlasResources) {
            if (!ids.add(resource.material())) throw duplicate(resource.material());
        }
        for (MaterialTextureResource resource : standaloneResources) {
            if (!ids.add(resource.material())) throw duplicate(resource.material());
        }
    }

    private static List<MaterialTextureResource> sorted(List<MaterialTextureResource> source,
                                                         MaterialTextureKind expectedKind) {
        ArrayList<MaterialTextureResource> result = new ArrayList<>(List.copyOf(source));
        for (MaterialTextureResource resource : result) {
            if (resource.kind() != expectedKind) {
                throw new IllegalArgumentException(resource.material() + " has kind " + resource.kind()
                        + " in " + expectedKind + " catalog");
            }
        }
        result.sort(java.util.Comparator.comparing(MaterialTextureResource::material));
        return List.copyOf(result);
    }

    private static IllegalArgumentException duplicate(ResourceId material) {
        return new IllegalArgumentException("Duplicate catalog material " + material);
    }
}
