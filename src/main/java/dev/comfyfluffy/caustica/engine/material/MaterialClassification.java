package dev.comfyfluffy.caustica.engine.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialVariant;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.OpenPbrMaterialProfile;

import java.util.Objects;

/** Host-adapter result used to select a precompiled material variant for one geometry resource. */
public record MaterialClassification(ResourceId geometry, OpenPbrMaterialProfile profile, boolean emitting) {
    public MaterialClassification {
        Objects.requireNonNull(profile, "profile");
    }

    public MaterialVariant variant(MaterialTopology topology) {
        return new MaterialVariant(profile, topology, emitting);
    }
}
