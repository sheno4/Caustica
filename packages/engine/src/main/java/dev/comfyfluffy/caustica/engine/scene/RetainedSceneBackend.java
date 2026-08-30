package dev.comfyfluffy.caustica.engine.scene;

import java.util.List;
import java.util.function.Supplier;
import java.util.Objects;

/** Publication seam for acceleration-structure and light-resource implementations. */
public interface RetainedSceneBackend {
    /** Updates current-frame rigid placements without waiting for ordered native geometry publication. */
    default void updateLatestInstanceTransforms(List<RetainedInstanceTransform> transforms) { }

    /**
     * Accepts one complete logical snapshot, reports when it becomes native-visible, and reports when the
     * displaced snapshot is no longer in use.
     * Native allocation, command construction, and submit acceptance happen before this method returns so
     * throwing rejects the logical mutation. Failures discovered after accepted GPU submission are session-fatal.
     * {@code published} runs exactly once after the native commit. Either callback may run synchronously, so
     * callers must not depend on post-call bookkeeping.
     */
    void publish(RetainedSceneSnapshot snapshot, Runnable published, Runnable previousRetired);

    /**
     * Accepts one atomic geometry delta. Backends with incremental native state consume the delta directly;
     * the fallback snapshot keeps simpler backends and tests on the complete-snapshot contract.
     */
    default void publishGeometry(RetainedSceneGeometryDelta delta,
                                 Supplier<RetainedSceneSnapshot> fallbackSnapshot,
                                 Runnable published, Runnable previousRetired) {
        publish(fallbackSnapshot.get(), published, previousRetired);
    }

    /**
     * Accepts geometry and content as one revision. The two retirement callbacks correspond to the
     * displaced geometry and content values and may run independently, although the default complete
     * snapshot implementation retires both at the same native boundary.
     */
    default void publishGeometryAndContent(RetainedSceneGeometryDelta geometry,
                                           RetainedSceneContentSnapshot content,
                                           Supplier<RetainedSceneSnapshot> fallbackSnapshot,
                                           Runnable published, Runnable previousGeometryRetired,
                                           Runnable previousContentRetired) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(content, "content");
        if (geometry.revision() != content.revision()) {
            throw new IllegalArgumentException("geometry and content revisions must match");
        }
        publish(fallbackSnapshot.get(), published,
                () -> retireBoth(previousGeometryRetired, previousContentRetired));
    }

    private static void retireBoth(Runnable first, Runnable second) {
        Throwable failure = null;
        try {
            first.run();
        } catch (Throwable callbackFailure) {
            failure = callbackFailure;
        }
        try {
            second.run();
        } catch (Throwable callbackFailure) {
            if (failure == null) failure = callbackFailure;
            else failure.addSuppressed(callbackFailure);
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("retained scene retirement failed", failure);
    }

    /**
     * Accepts light and environment content while reusing the preceding publication's native geometry.
     * Content and full snapshots share one strictly increasing revision order. The callback contract is
     * identical to {@link #publish(RetainedSceneSnapshot, Runnable, Runnable)}.
     */
    void publishContent(RetainedSceneContentSnapshot snapshot, Runnable published, Runnable previousRetired);

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

    /**
     * Settles accepted GPU work after every session contribution has been invalidated and before its
     * retirement drain begins. No later frame may consume this backend after the boundary returns.
     */
    default void prepareForSessionClose() { }
}
