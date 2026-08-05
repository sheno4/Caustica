package dev.comfyfluffy.caustica.api.pass;

import java.util.Objects;

public record DispatchImage(String binding, ImageRef image) {
    public DispatchImage {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(image, "image");
    }

    public static DispatchImage bind(String binding, ImageRef image) {
        return new DispatchImage(binding, image);
    }
}
