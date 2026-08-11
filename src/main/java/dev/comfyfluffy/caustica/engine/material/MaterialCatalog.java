package dev.comfyfluffy.caustica.engine.material;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministically ordered material assets submitted by one scene adapter for a resource epoch. */
public record MaterialCatalog(List<MaterialTextureAsset> atlasAssets,
                              List<MaterialTextureAsset> standalone,
                              ResourceId defaultUniformEmissionAsset,
                              float defaultUniformEmissionLuminanceCdM2) {
    public MaterialCatalog {
        atlasAssets = sorted(atlasAssets, MaterialTextureKind.SHARED_ATLAS);
        standalone = sorted(standalone, MaterialTextureKind.STANDALONE);
        Set<ResourceId> ids = new HashSet<>();
        for (MaterialTextureAsset asset : atlasAssets) {
            if (!ids.add(asset.material())) throw duplicate(asset.material());
        }
        for (MaterialTextureAsset asset : standalone) {
            if (!ids.add(asset.material())) throw duplicate(asset.material());
        }
        if (defaultUniformEmissionAsset != null && !ids.contains(defaultUniformEmissionAsset)) {
            throw new IllegalArgumentException("Default uniform-emission asset is absent from the catalog: "
                    + defaultUniformEmissionAsset);
        }
        if (!Float.isFinite(defaultUniformEmissionLuminanceCdM2)
                || defaultUniformEmissionLuminanceCdM2 <= 0.0f) {
            throw new IllegalArgumentException("Default uniform-emission luminance must be positive");
        }
    }

    private static List<MaterialTextureAsset> sorted(List<MaterialTextureAsset> source,
                                                      MaterialTextureKind expectedKind) {
        ArrayList<MaterialTextureAsset> result = new ArrayList<>(List.copyOf(source));
        for (MaterialTextureAsset asset : result) {
            if (asset.kind() != expectedKind) {
                throw new IllegalArgumentException(asset.material() + " has kind " + asset.kind()
                        + " in " + expectedKind + " catalog");
            }
        }
        result.sort(java.util.Comparator.comparing(MaterialTextureAsset::material));
        return List.copyOf(result);
    }

    private static IllegalArgumentException duplicate(ResourceId material) {
        return new IllegalArgumentException("Duplicate catalog material " + material);
    }
}
