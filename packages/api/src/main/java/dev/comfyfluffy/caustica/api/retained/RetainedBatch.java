package dev.comfyfluffy.caustica.api.retained;

import dev.comfyfluffy.caustica.api.resource.ResourceGeneration;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;

import java.util.List;
import java.util.Objects;

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
 * <h2>Acceptance is synchronous; retirement follows what the batch retained</h2>
 *
 * Acceptance is decided before {@code submit} returns and rejection is reported by throwing. After an
 * accepted drop returns, the caller may discard its id. Rejection does not transfer the callback or source
 * resources covered by this batch. Every accepted callback is scheduled exactly once and never runs inline.
 *
 * <p>{@link #retired()} is the batch's asynchronous signal: it runs once every value this batch introduced
 * has later been replaced, dropped, discarded because a GPU build failed, or removed by a scene cascade, and no
 * submitted GPU work still reads it. Those are different instants, usually frames apart. A batch that
 * only drops values may use a no-op callback; the earlier batches that introduced those values report their
 * retirement.
 *
 * <h2>Batch-scoped retirement</h2>
 *
 * The callback is an exactly-once fan-in for the retained logical values introduced by the batch. It also
 * covers storage reached through values carrying {@link ResourceRef#none()}. A non-NONE
 * {@link ResourceGeneration} has its own retirement callback and may be shared across any number of
 * operations and batches; its retirement is independent of every batch callback.
 *
 * <h2>Ordering</h2>
 *
 * Batches from one source apply in submission order. The renderer may coalesce internally — skipping an
 * acceleration build already obsoleted by a later batch — but that is invisible: each accepted batch still
 * retires exactly once after the data it introduced can no longer be read.
 *
 * <p>Retirement callbacks from one session are serialized in retirement order on an
 * implementation-selected callback thread. A callback must return promptly and must not throw. Retirement
 * may require render-thread progress, so callers must use callbacks instead of blocking waits.
 *
 * @param <O> the operation type of the channel this batch is submitted to
 */
public record RetainedBatch<O>(List<O> operations, Runnable retired) {
    public RetainedBatch {
        operations = List.copyOf(operations);
        Objects.requireNonNull(retired, "retired");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("a batch needs at least one operation");
        }
    }

    /** A batch for which the caller requires no retirement notification. */
    public static <O> RetainedBatch<O> of(List<O> operations) {
        return new RetainedBatch<>(operations, () -> { });
    }
}
