package dev.comfyfluffy.caustica.engine.light;

import java.util.List;

/**
 * Immutable renderer-facing skeleton for retained finite-light data.
 * Generation changes identify content changes without comparing the batch list each frame.
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
