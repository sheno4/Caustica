package dev.comfyfluffy.caustica.engine.scene;

/** Publication seam for acceleration-structure and light-resource implementations. */
@FunctionalInterface
public interface RetainedSceneBackend {
    /**
     * Accepts one complete logical snapshot and reports when the displaced snapshot is no longer in use.
     * Native allocation, command construction, and submit acceptance happen before this method returns so
     * throwing rejects the logical mutation. Failures discovered after accepted GPU submission are session-fatal.
     * The retirement callback may run synchronously, so callers must not depend on post-call bookkeeping.
     */
    void publish(RetainedSceneSnapshot snapshot, Runnable previousRetired);
}
