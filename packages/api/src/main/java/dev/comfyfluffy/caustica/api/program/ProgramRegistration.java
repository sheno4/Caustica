package dev.comfyfluffy.caustica.api.program;

import java.util.function.Consumer;

/**
 * Owner capability, typed exports, and readiness observation for one atomic program set.
 *
 * <p>The registration completes with {@link Ready} only after an active world program contains the complete
 * set, {@link Failed} if none of that set can publish, or {@link Cancelled} if it closes or its contribution
 * ends first. A failed set leaves the last ready world program active. Failure is isolated per registration
 * in acceptance order, and unaffected later sets are retried against the last successful composition.
 *
 * <p>The render-session contribution owns guaranteed cleanup. Closing explicitly requests earlier removal
 * of the complete set and is idempotent. Terminal transitions, callback registration, close, and publication
 * are linearized. Once observed, a terminal state never changes. All methods are thread-safe.
 */
public interface ProgramRegistration<E> extends AutoCloseable {
    /** The source-defined value returned by the synchronous declaration callback. */
    E exports();

    /**
     * Registers one completion callback. Calling this more than once registers independent callbacks.
     *
     * <p>Compilation may require render-thread progress and must not be awaited by blocking that thread.
     * Completion callbacks from one session are serialized in completion order on an
     * implementation-selected callback thread. If this registration is already complete, the callback is
     * scheduled rather than invoked inline. A callback must return promptly and must not throw; one callback
     * cannot prevent later notifications. Every accepted registration reaches exactly one terminal state, and
     * callbacks registered before contribution teardown return before the contribution's final close begins.
     */
    void whenComplete(Consumer<? super Completion> callback);

    /**
     * Request removal of the complete set.
     *
     * <p>If completion is still pending, observers receive {@link Cancelled} and the set never publishes. If
     * it is ready, removal occurs at a later publication boundary. Failed and cancelled registrations have
     * no live implementation to remove. In every case, accepted shader-data claims remain retained
     * until their asynchronous uses end.
     *
     * <p>Close and readiness publication are linearized. If close wins, it returns after the registration has
     * become cancelled and the set can no longer publish. If publication wins, the registration becomes ready and
     * close returns after scheduling its later removal. Close does not wait for GPU retirement.
     */
    @Override
    void close();

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
