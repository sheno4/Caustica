package dev.comfyfluffy.caustica.api.pass;

import net.minecraft.resources.Identifier;

import java.util.Objects;

public record ImageRef(Identifier id, int level) {
    public ImageRef {
        Objects.requireNonNull(id, "id");
        if (level < 0) {
            throw new IllegalArgumentException("image level must be non-negative");
        }
    }
}
