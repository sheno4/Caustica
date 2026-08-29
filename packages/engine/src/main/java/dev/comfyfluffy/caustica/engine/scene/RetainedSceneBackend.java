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

    /**
     * Advances accepted publications on the session-control thread. Backends which complete work
     * asynchronously must expose those completions here rather than invoking retirement callbacks
     * from a device-completion thread.
     */
    default void progress() { }

    /**
     * Installs the wakeup used when {@link #progress()} can make new progress. The callback only
     * signals the session-control thread and must not publish or retire resources itself.
     */
    default void onProgressAvailable(Runnable wakeup) { }
}
