package dev.comfyfluffy.caustica.engine.material;

import java.util.Objects;

/** Host-neutral axes selecting one precompiled material binding variant. */
public record MaterialVariant(OpenPbrMaterialProfile profile, boolean transmissive, boolean emitting) {
    public MaterialVariant {
        Objects.requireNonNull(profile, "profile");
    }
}
