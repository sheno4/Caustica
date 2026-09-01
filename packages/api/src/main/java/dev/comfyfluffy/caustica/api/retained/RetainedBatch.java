package dev.comfyfluffy.caustica.api.retained;

import java.util.List;

/**
 * A set of operations against one retained collection that becomes visible together or not at all.
 *
 * <p>Geometry and light channels accept batches. A batch may span scenes because each operation identifies
 * its target scene.
 *
 * <h2>Atomic publication</h2>
 *
 * A mesh cannot publish until its acceleration structure is built, so the slowest operation delays the
 * entire batch. Group only operations that must become visible together. Placement-only batches publish at
 * the next update boundary unless grouped with work that requires a build.
 *
 * <h2>Ordering</h2>
 *
 * Batches from one source apply in submission order. The renderer may coalesce internally — skipping an
 * acceleration build already obsoleted by a later batch — but that is invisible to the logical collection.
 *
 * @param <O> the operation type of the channel this batch is submitted to
 */
public record RetainedBatch<O>(List<O> operations) {
    public RetainedBatch {
        operations = List.copyOf(operations);
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("a batch needs at least one operation");
        }
    }

    public static <O> RetainedBatch<O> of(List<O> operations) {
        return new RetainedBatch<>(operations);
    }
}
