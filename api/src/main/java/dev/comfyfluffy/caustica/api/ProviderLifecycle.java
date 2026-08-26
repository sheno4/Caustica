package dev.comfyfluffy.caustica.api;

/** Lifecycle shared by every provider created for one runtime activation. */
public interface ProviderLifecycle {
    /** Called when the host replaces the world represented by the active runtime session. */
    default void onWorldChanged() {
    }

    /** Called while the current resource pack is being detached. */
    default void onResourcePackClosing() {
    }

    /** Called after a replacement resource pack becomes active. */
    default void onResourcePackApplied() {
    }

    /**
     * Stop producing work for this runtime activation. Submissions made after this return are ignored.
     * Retained geometry is still published and still being read, so nothing may be destroyed here — hand
     * anything that must go to {@link dev.comfyfluffy.caustica.api.gpu.GpuDevice#retireAfterUse}.
     *
     * <p>Separate from {@link #shutdown()} for the CPU side: a provider with worker threads uses this to
     * stop dispatching and that to release once they have joined. A provider without them can leave this
     * empty.
     */
    default void stop() {
    }

    /**
     * Release this activation's state. The renderer has drained its queues, dropped everything this
     * provider retained, and run every outstanding retirement callback — so by the time this is called,
     * each accepted {@code SceneMesh}'s {@code retired()} has already fired exactly once, and no further
     * callback will reach this instance.
     *
     * <p>That is what makes a shared arena tractable: a provider suballocating many meshes from one buffer
     * refcounts through the per-mesh retirements and frees the arena here, unconditionally.
     */
    default void shutdown() {
    }
}
