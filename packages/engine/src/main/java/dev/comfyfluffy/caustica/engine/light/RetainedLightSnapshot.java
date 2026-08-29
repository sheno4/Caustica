package dev.comfyfluffy.caustica.engine.light;

import java.util.List;

/**
 * Immutable terrain-light publication input. Generation changes identify content changes without
 * comparing every section each frame.
 */
public record RetainedLightSnapshot(List<RetainedLightBatch> batches, long generation) {
    public RetainedLightSnapshot {
        batches = List.copyOf(batches);
    }

    public static RetainedLightSnapshot empty(long generation) {
        return new RetainedLightSnapshot(List.of(), generation);
    }

    public boolean isEmpty() {
        return batches.isEmpty();
    }
}
