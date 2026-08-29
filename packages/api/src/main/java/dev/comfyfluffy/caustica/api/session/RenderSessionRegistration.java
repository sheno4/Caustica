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
     * <p>This API does not expose teardown completion. Teardown may depend on render-thread progress, so
     * callers must not block the render thread after closing the registration.
     */
    @Override
    void close();
}
