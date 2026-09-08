package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.support.SharedResource;

/** Render-owned predecessor claim, advanced only by successful submission callbacks. */
final class RtFeedbackHistory<T> implements AutoCloseable {
    private SharedResource<T> previous;
    private boolean retired;

    SharedResource<T> capture() { return previous == null ? null : previous.retain(); }

    void submitted(SharedResource<T> frame) {
        if (retired) return;
        var old = previous;
        previous = frame.retain();
        if (old != null) old.close();
    }

    @Override public void close() {
        retired = true;
        var released = previous;
        previous = null;
        if (released != null) released.close();
    }
}
