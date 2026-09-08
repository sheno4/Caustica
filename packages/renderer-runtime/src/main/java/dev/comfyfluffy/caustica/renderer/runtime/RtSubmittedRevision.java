package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.support.SharedResource;

/** One view's actual submitted predecessor remains owned independently of GPU frame retirement. */
final class RtSubmittedRevision<T> implements AutoCloseable {
    private SharedResource<T> previous;

    SharedResource<T> acquire() { return previous == null ? null : previous.retain(); }

    void submitted(SharedResource<T> current) {
        var displaced = previous;
        previous = current.retain();
        if (displaced != null) displaced.close();
    }

    @Override public void close() {
        var released = previous;
        previous = null;
        if (released != null) released.close();
    }
}
