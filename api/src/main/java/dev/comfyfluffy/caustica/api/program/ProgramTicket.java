package dev.comfyfluffy.caustica.api.program;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Non-blocking observation of one requested world-program composition.
 *
 * <p>The renderer may coalesce several changes into the same compilation; their tickets then complete
 * together. A failed composition leaves the last ready composition active. A newly issued id which never
 * became ready continues to resolve to the visible error implementation.
 *
 * <p>There is deliberately no wait method. Compilation completion can depend on render-thread progress,
 * so blocking that thread would deadlock. {@link #whenComplete} registers once and returns immediately.
 * Completion callbacks from one session are serialized in completion order on an implementation-selected
 * callback thread, never assumed to be the render thread. If already complete, the implementation schedules
 * the callback rather than invoking extension code inline. A callback must return promptly and must not
 * throw; one callback cannot prevent later notifications.
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
        FAILED
    }

    sealed interface Completion permits Ready, Failed { }

    record Ready() implements Completion { }

    record Failed(ProgramFailure failure) implements Completion {
        public Failed {
            java.util.Objects.requireNonNull(failure, "failure");
        }
    }
}
