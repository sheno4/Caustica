package dev.comfyfluffy.caustica.api.program;

/**
 * Owner capability, typed exports, and readiness observation for one atomic program set.
 *
 * <p>The render-session contribution owns guaranteed cleanup. Closing explicitly requests earlier removal
 * of the complete set and is idempotent. All methods are thread-safe.
 */
public interface ProgramRegistration<E> extends AutoCloseable {
    /** The source-defined value returned by the synchronous declaration callback. */
    E exports();

    /** Readiness of this complete set in the composed world program. */
    ProgramTicket readiness();

    /**
     * Request removal of the complete set.
     *
     * <p>If readiness is still pending, it becomes cancelled and the set never publishes. If it is ready,
     * the state remains ready—it records successful publication—and removal occurs at a later publication
     * boundary. Failed and cancelled registrations have no live implementation to remove. In every case,
     * accepted definition callbacks retain their ordinary asynchronous retirement guarantees.
     *
     * <p>Close and readiness publication are linearized. If close wins, it returns after the ticket has
     * become cancelled and the set can no longer publish. If publication wins, the ticket becomes ready and
     * close returns after scheduling its later removal. Close does not wait for GPU retirement.
     */
    @Override
    void close();
}
