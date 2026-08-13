package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.engine.light.LightDescriptor;

import java.util.List;

/**
 * Immutable owner-local group of retained finite lights. Lights carry absolute scene coordinates; the
 * slot is the stable owner-local table index, and sorting by it is what makes a rebuild's record order —
 * and therefore the published generation — reproducible from an unordered set of batches.
 */
public record RetainedLightBatch(int slot, List<LightDescriptor.Finite> lights) {
    public RetainedLightBatch {
        if (slot < 0) throw new IllegalArgumentException("slot must be non-negative");
        lights = List.copyOf(lights);
    }
}
