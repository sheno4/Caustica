package dev.comfyfluffy.caustica.api.provider;

import java.util.Objects;

/** Host-neutral axes selecting one precompiled material binding variant. */
public record MaterialVariant(OpenPbrMaterialProfile profile, MaterialTopology topology, boolean emitting) {
    public MaterialVariant {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(topology, "topology");
    }
}
