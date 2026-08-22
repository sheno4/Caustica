package dev.comfyfluffy.caustica.engine.light;

import dev.comfyfluffy.caustica.api.provider.LightDescriptor;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.List;

/** One source-qualified retained finite-light group collected from the public provider API. */
public record RetainedLightBatch(ResourceId source, long key, long revision,
                                 List<LightDescriptor.Finite> lights) {
    public RetainedLightBatch {
        java.util.Objects.requireNonNull(source, "source");
        lights = List.copyOf(lights);
    }
}
