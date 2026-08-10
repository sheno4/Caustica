package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.engine.light.LightDescriptor;

import java.util.List;

/**
 * Immutable owner-local group of retained finite lights sharing one proposal-grid cell. The slot is
 * the stable owner-local table index; cell coordinates use the renderer's 16-scene-unit grid.
 */
public record RetainedLightBatch(int slot, int cellX, int cellY, int cellZ,
                                 List<LightDescriptor.Finite> lights) {
    public RetainedLightBatch {
        if (slot < 0) throw new IllegalArgumentException("slot must be non-negative");
        lights = List.copyOf(lights);
    }
}
