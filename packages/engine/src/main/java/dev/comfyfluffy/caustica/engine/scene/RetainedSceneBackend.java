package dev.comfyfluffy.caustica.engine.scene;

import java.util.List;
import java.util.function.Supplier;

/** Publication seam for acceleration-structure and light-resource implementations. */
public interface RetainedSceneBackend {
    /** Updates current-frame rigid placements without waiting for ordered native geometry publication. */
    default void updateLatestInstanceTransforms(List<RetainedInstanceTransform> transforms) { }

    /**
     * Accepts one complete logical snapshot and reports when its native work has settled.
     * Native allocation, command construction, and submit acceptance happen before this method returns so
     * throwing rejects the logical mutation. {@code published} runs exactly once afterwards, and backends
     * may report it out of revision order: a publication needing no GPU work can settle ahead of an earlier
     * one that is still building. Either callback may run synchronously, so callers must not depend on
     * post-call bookkeeping.
     *
     * <p>A backend may make accepted mutations logically visible before their acceleration structures exist,
     * rendering the affected geometry from its previous native generation until the build lands. Reaching
     * {@code published} therefore means the batch settled, not that every mutation in it is on screen.
     */
    void publish(RetainedSceneSnapshot snapshot, Runnable published);

    /**
     * Accepts one atomic geometry delta. Backends with incremental native state consume the delta directly;
     * the fallback snapshot keeps simpler backends and tests on the complete-snapshot contract.
     */
    default void publishGeometry(RetainedSceneGeometryDelta delta,
                                 Supplier<RetainedSceneSnapshot> fallbackSnapshot,
                                 Runnable published) {
        publish(fallbackSnapshot.get(), published);
    }

    /**
     * Accepts geometry and content as one revision.
     */
    default void publishGeometryAndContent(RetainedSceneGeometryDelta geometry,
                                           RetainedSceneContentSnapshot content,
                                           Supplier<RetainedSceneSnapshot> fallbackSnapshot,
                                           Runnable published) {
        java.util.Objects.requireNonNull(geometry, "geometry");
        java.util.Objects.requireNonNull(content, "content");
        if (geometry.revision() != content.revision()) {
            throw new IllegalArgumentException("geometry and content revisions must match");
        }
        publish(fallbackSnapshot.get(), published);
    }

    /**
     * Accepts light and environment content while reusing the preceding publication's native geometry.
     * Content and full snapshots share one strictly increasing revision order. The callback contract is
     * identical to {@link #publish(RetainedSceneSnapshot, Runnable)}.
     */
    void publishContent(RetainedSceneContentSnapshot snapshot, Runnable published);

    /**
     * Advances accepted publications on the session-control thread. Backends which complete work
     * asynchronously must expose those completions here rather than invoking publication callbacks
     * from a device-completion thread.
     */
    default void progress() { }

    /**
     * Installs the wakeup used when {@link #progress()} can make new progress. The callback only
     * signals the session-control thread and must not publish or retire resources itself.
     */
    default void onProgressAvailable(Runnable wakeup) { }

    /** Settles frame-held renderer leases when an owner drain cannot advance without GPU completion. */
    default void settleFrameUses() { }

    /**
     * Settles accepted GPU work after every session contribution has been invalidated and before its
     * retirement drain begins. No later frame may consume this backend after the boundary returns.
     */
    default void prepareForSessionClose() { }
}
