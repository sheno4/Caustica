package dev.comfyfluffy.caustica.api.session;

/** Process-lived registration of one render-session factory. */
public interface RenderSessionRegistration extends AutoCloseable {
    /**
     * Prevents future sessions from using the factory and synchronously tears down its active contribution
     * using the normal stop, scoped retirement, drain, and close ordering. It must not be called from a
     * callback belonging to that contribution. Concurrent calls and calls racing ordinary session shutdown
     * have the same single effect; this method is idempotent.
     */
    @Override
    void close();
}
