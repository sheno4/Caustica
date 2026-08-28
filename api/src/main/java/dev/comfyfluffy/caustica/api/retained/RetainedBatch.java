package dev.comfyfluffy.caustica.api.retained;

import java.util.List;
import java.util.Objects;

/**
 * A set of operations against one retained collection that becomes visible together or not at all.
 *
 * <p><b>Scene contents take these, and nothing else does.</b> Geometry and lights are what a frame's
 * picture is made of, so a half-applied change to them is a frame someone sees wrong. A surface
 * implementation or environment is a program entry, and an entry nothing names changes nothing.
 * They are added synchronously with no batch, and the atomicity their replacement needs is carried by the
 * geometry batch that starts naming them.
 *
 * <p>A batch may span scenes: it is a set of operations against one collection, and which scene each
 * operation names is part of the operation, not of the batch.
 *
 * <h2>Atomicity is the source's choice, and it is not free</h2>
 *
 * A mesh cannot publish until its acceleration structure is built, so the slowest member gates the rest.
 * Group what must not be seen apart — two sections sharing a seam, every mesh in a resource reload — and
 * keep everything else in its own batch. Placement-only batches especially: they need no build and publish
 * at the next update boundary unless something heavier is mixed in to hold them back.
 *
 * <h2>Acceptance is synchronous; retirement follows what the batch retained</h2>
 *
 * Whether a batch was accepted is decided before {@code submit} returns and reported by throwing, so a
 * source knows on the calling thread whether it may update its own bookkeeping. That is what makes it safe
 * to drop something and immediately forget its id. A rejected submission does not take the callback or any
 * source resource. Every accepted callback is scheduled exactly once, never inline, including when session
 * teardown or a later GPU build failure removes the introduced values.
 *
 * <p>{@link #retired()} is the one asynchronous signal: it runs once every value this batch introduced has
 * later been replaced, dropped, discarded because a GPU build failed, or removed by a scene cascade, and no
 * submitted GPU work still reads it. Those are different instants, usually frames apart. A batch that
 * only drops values introduces no resource borrow and may use a no-op callback; the callbacks belonging to
 * the earlier batches that introduced those values report their retirement.
 *
 * <h2>Why retirement is batch-scoped</h2>
 *
 * Atomic publication already ties the introduced values together. One callback keeps that same lifetime:
 * it runs after the last value from the batch retires. If two resources should be reclaimed independently,
 * submit them in separate batches; if an allocation is shared across batches, the source refcounts those
 * borrows in their callbacks.
 *
 * <h2>Ordering</h2>
 *
 * Batches from one source apply in submission order. The renderer may coalesce internally — skipping an
 * acceleration build already obsoleted by a later batch — but that is invisible: each accepted batch still
 * retires exactly once after the data it introduced can no longer be read.
 *
 * <p>Retirement callbacks from one session are serialized in retirement order on an
 * implementation-selected callback thread. They are non-blocking notifications, not render-thread work:
 * a callback must return promptly and must not throw. There is no waiting API because retirement may
 * require render-thread progress.
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
