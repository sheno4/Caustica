package dev.comfyfluffy.caustica.api;

import java.util.List;
import java.util.Objects;

/**
 * A set of operations against one retained collection that becomes visible together or not at all.
 *
 * <p>Every retained channel — geometry, lights — takes these, so the contract is written once here rather
 * than per channel. A batch may span scenes: it is a set of operations against one collection, and which
 * scene each operation names is part of the operation, not of the batch.
 *
 * <h2>Atomicity is the source's choice, and it is not free</h2>
 *
 * A mesh cannot publish until its acceleration structure is built, so the slowest member gates the rest.
 * Group what must not be seen apart — two sections sharing a seam, every mesh in a resource reload — and
 * keep everything else in its own batch. Placement-only batches especially: they need no build and publish
 * at the next update boundary unless something heavier is mixed in to hold them back.
 *
 * <h2>Acceptance is synchronous; retirement is not</h2>
 *
 * Whether a batch was accepted is decided before {@code submit} returns and reported by throwing, so a
 * source knows on the calling thread whether it may update its own bookkeeping. That is what makes it safe
 * to drop something and immediately forget its id.
 *
 * <p>{@link #retired()} is the one asynchronous signal: it runs once everything this batch displaced —
 * replaced, dropped — has left the collection <em>and</em> no submitted GPU work still reads it. Those are
 * different instants, usually frames apart.
 *
 * <h2>Why retirement is batch-scoped rather than per operation</h2>
 *
 * It costs nothing, because atomicity has already tied the fates together. A batch that replaces one mesh
 * and drops another keeps the dropped one placed, and therefore read, until the replacement is ready
 * — so both become unreferenced at the same instant regardless of where the callback hangs.
 *
 * <p>And it is the only scope a source can write correctly. Superseding a mesh usually frees the old build
 * while keeping per-mesh resources that will be reused; dropping it frees everything. Only the caller knows
 * which, and at submit time it does. A callback attached to an operation would run identically in both
 * cases with nothing to tell them apart.
 *
 * <h2>Ordering</h2>
 *
 * Batches from one source apply in submission order. The renderer may coalesce internally — skipping an
 * acceleration build already obsoleted by a later batch — but that is invisible: each batch still reports
 * what it displaced.
 *
 * @param <O> the operation type of the channel this batch is submitted to
 */
public record AtomicBatch<O>(List<O> operations, Runnable retired) {
    public AtomicBatch {
        operations = List.copyOf(operations);
        Objects.requireNonNull(retired, "retired");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("a batch needs at least one operation");
        }
    }

    /** A batch that displaces nothing, so nothing has to come back. */
    public static <O> AtomicBatch<O> of(List<O> operations) {
        return new AtomicBatch<>(operations, () -> { });
    }
}
