package dev.comfyfluffy.caustica.api.provider;

/** Handle for optional early removal of one session-scoped scene provider registration. */
public interface SceneProviderRegistration extends AutoCloseable {
    /**
     * Stops future callbacks and waits for a callback already executing. No callback is running or can
     * begin when this method returns. Idempotent; must not be called from this provider's callback.
     */
    @Override
    void close();
}
