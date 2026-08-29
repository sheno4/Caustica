package dev.comfyfluffy.caustica.api.program;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Non-blocking readiness of one complete {@link ProgramRegistration program registration}.
 *
 * <p>The ticket describes the owner's accepted set, not an intermediate compiler composition. It becomes
 * {@link State#READY} only after an active world program contains the complete set, {@link State#FAILED} if
 * that set cannot be published, or {@link State#CANCELLED} if its registration closes or its contribution
 * ends first. A failed set publishes none of its declarations and leaves the last ready world program active;
 * its ids remain permanent fallback references and its accepted retirement callbacks are scheduled. Failure
 * is isolated per registration in acceptance order: a combined compiler candidate cannot fail an otherwise
 * publishable set, and unaffected later sets are retried against the last successful composition.
 *
 * <p>Observe completion with {@link #whenComplete}; compilation may require render-thread progress and must
 * not be awaited by blocking that thread.
 * Completion callbacks from one session are serialized in completion order on an implementation-selected
 * callback thread, never assumed to be the render thread. If already complete, the implementation schedules
 * the callback without invoking extension code inline. A callback must return promptly and must not
 * throw; one callback cannot prevent later notifications. Every accepted ticket reaches exactly one
 * terminal state, and callbacks registered before contribution teardown return before
 * {@code RenderSessionContribution.close()} begins.
 *
 * <p>All methods are thread-safe. Terminal state transitions, callback registration, registration close,
 * and program publication are linearized. Once any method observes a terminal state it never changes.
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
        CANCELLED
    }

    sealed interface Completion permits Ready, Failed, Cancelled { }

    record Ready() implements Completion { }

    record Failed(ProgramFailure failure) implements Completion {
        public Failed {
            java.util.Objects.requireNonNull(failure, "failure");
        }
    }

    /** The registration closed or its contribution ended before the complete set became active. */
    record Cancelled() implements Completion { }
}
