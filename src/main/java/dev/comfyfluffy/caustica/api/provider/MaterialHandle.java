package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.Objects;

/** Stable material resource referenced by provider geometry and resolved for each material epoch. */
public record MaterialHandle(ResourceId id) {
    public MaterialHandle {
        Objects.requireNonNull(id, "id");
    }

    public static MaterialHandle of(String namespace, String path) {
        return new MaterialHandle(ResourceId.of(namespace, path));
    }
}
