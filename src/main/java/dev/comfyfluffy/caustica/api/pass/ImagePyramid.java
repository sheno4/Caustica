package dev.comfyfluffy.caustica.api.pass;

import net.minecraft.resources.Identifier;

import java.util.Objects;

public record ImagePyramid(Identifier id) {
    public ImagePyramid {
        Objects.requireNonNull(id, "id");
    }

    public ImageRef level(int level) {
        return new ImageRef(id, level);
    }
}
