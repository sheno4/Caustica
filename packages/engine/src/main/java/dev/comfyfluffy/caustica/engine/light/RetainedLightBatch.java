package dev.comfyfluffy.caustica.engine.light;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;

import java.util.List;

/** One immutable terrain section's finite lights, ready for retained-channel publication. */
public record RetainedLightBatch(long sectionKey, long revision, List<LightDescriptor.Finite> lights) {
    public RetainedLightBatch {
        lights = List.copyOf(lights);
    }
}
