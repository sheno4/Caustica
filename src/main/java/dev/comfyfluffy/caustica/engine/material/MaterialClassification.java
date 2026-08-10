package dev.comfyfluffy.caustica.engine.material;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.Objects;

/** Host-adapter result used to select a precompiled material variant for one geometry resource. */
public record MaterialClassification(ResourceId geometry, OpenPbrMaterialProfile profile, boolean emitting) {
    public MaterialClassification {
        Objects.requireNonNull(profile, "profile");
    }
}
