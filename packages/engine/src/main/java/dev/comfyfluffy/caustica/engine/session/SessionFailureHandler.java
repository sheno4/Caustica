package dev.comfyfluffy.caustica.engine.session;

/** Receives isolated lifecycle failures on the session-control thread. The callback must not throw. */
@FunctionalInterface
public interface SessionFailureHandler {
    void report(SessionFailure failure);
}
