package dev.comfyfluffy.caustica.api.program;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Non-blocking observation of one requested world-program composition.
 *
 * <p>The ticket names the exact composition requested by one accepted operation. If a later operation is
 * accepted before that exact composition becomes active, the renderer may coalesce the changes and complete
 * the earlier ticket as {@link State#SUPERSEDED}; the later ticket observes the combined composition. A
 * successful candidate is published atomically. A failed candidate publishes none of its changes and leaves
 * the last ready composition active. Additions introduced by that candidate are abandoned: their ids remain
 * permanent fallback references and their accepted retirement callbacks are scheduled. Drops in that
 * candidate leave their implementations live and become requestable again. Earlier tickets coalesced into
 * the candidate are already {@link State#SUPERSEDED}; its final ticket is {@link State#FAILED}. A later
 * request starts from the last ready composition rather than inheriting failed changes.
 *
 * <p>If an addition is dropped before either change publishes, coalescing may reduce both to a no-op: the
 * addition ticket is superseded, the drop ticket becomes ready, the id remains a fallback reference, and
 * the addition's retirement callback is scheduled.
 * A ticket still pending when its contribution begins teardown becomes {@link State#CANCELLED}.
 *
 * <p>Observe completion with {@link #whenComplete}; compilation may require render-thread progress and must
 * not be awaited by blocking that thread.
 * Completion callbacks from one session are serialized in completion order on an implementation-selected
 * callback thread, never assumed to be the render thread. If already complete, the implementation schedules
 * the callback without invoking extension code inline. A callback must return promptly and must not
 * throw; one callback cannot prevent later notifications. Every accepted ticket reaches exactly one
 * terminal state, and callbacks registered before contribution teardown return before
 * {@code RenderSessionContribution.close()} begins.
 */
public interface ProgramTicket {
    State state();

    /** Present exactly when {@link #state()} is {@link State#FAILED}. */
    Optional<ProgramFailure> failure();

    /** Register one completion callback. Calling this more than once registers independent callbacks. */
    void whenComplete(Consumer<? super Completion> callback);

    enum State {
        PENDING,
        READY,
        FAILED,
        SUPERSEDED,
        CANCELLED
    }

    sealed interface Completion permits Ready, Failed, Superseded, Cancelled { }

    record Ready() implements Completion { }

    record Failed(ProgramFailure failure) implements Completion {
        public Failed {
            java.util.Objects.requireNonNull(failure, "failure");
        }
    }

    /**
     * A later accepted request made this exact intermediate composition unnecessary. This says nothing
     * about object lifetime: the later combined candidate may still contain this ticket's change.
     */
    record Superseded() implements Completion { }

    /** The contribution ended before this requested composition could become active. */
    record Cancelled() implements Completion { }
}
