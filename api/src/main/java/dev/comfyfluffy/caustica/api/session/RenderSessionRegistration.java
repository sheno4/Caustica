package dev.comfyfluffy.caustica.api.session;

/** Process-lived registration of one render-session factory. */
public interface RenderSessionRegistration extends AutoCloseable {
    /**
     * Requests removal of the factory and teardown of its active contributions using the normal stop, scoped
     * retirement, drain, and close ordering. Once the request is accepted, future sessions do not use the
     * factory. This method returns without waiting for active teardown and is safe to call from a contribution
     * callback. Concurrent calls and calls racing ordinary session shutdown have the same single effect; this
     * method is idempotent.
     *
     * <p>There is deliberately no wait method. Teardown completion can depend on render-thread progress. If
     * completion observation is added, it must use asynchronously scheduled callbacks and must not invoke
     * extension code inline.
     */
    @Override
    void close();
}
