package dev.comfyfluffy.caustica.api;

/** Lifecycle of one registered provider instance. */
public interface ProviderLifecycle {
    /** Called when the host replaces the world the active session represents. */
    default void onWorldChanged() {
    }

    /** Called while the host's content set is being detached. */
    default void onContentClosing() {
    }

    /** Called after replacement host content becomes active. */
    default void onContentApplied() {
    }

    /**
     * Stop producing work for this runtime activation, join producer threads, and submit drops for every
     * retained id this provider owns. No submission may originate from this provider after the method
     * returns. GPU resources may still be read, so nothing is destroyed here; their retained-data
     * callbacks report when they are free.
     *
     * <p>The renderer cannot enforce the no-more-submissions rule because channels are process-wide rather
     * than provider-scoped. This callback is synchronous so the retirement boundary can be placed after
     * every submission the provider made.
     */
    default void stop() {
    }

    /**
     * Release this provider's state. The renderer has drained submissions accepted before {@link #stop()}
     * and run their outstanding retirement callbacks, so no further callback will reach this instance.
     *
     * <p>Registration supplies lifecycle notification and frame coherence; it does not make the renderer
     * the owner of channel submissions.
     */
    default void shutdown() {
    }
}
