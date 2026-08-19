package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.Objects;

/** Runtime atlas region paired with a catalog-compiled material template. */
public record AtlasMaterialReference(ResourceId material, ResourceId atlas, MaterialUv albedoUv) {
    public AtlasMaterialReference {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(atlas, "atlas");
        Objects.requireNonNull(albedoUv, "albedoUv");
    }
}
