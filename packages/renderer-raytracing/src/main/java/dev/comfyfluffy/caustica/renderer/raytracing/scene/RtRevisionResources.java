package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import java.util.ArrayDeque;

/** Releases owned resources in reverse registration order, including when a release fails. */
final class RtRevisionResources implements AutoCloseable {
    private final ArrayDeque<Runnable> releases = new ArrayDeque<>();

    void add(Runnable release) { releases.push(release); }

    @Override public void close() {
        Throwable failure = null;
        while (!releases.isEmpty()) {
            try { releases.pop().run(); }
            catch (Throwable cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("revision retirement failed", failure);
    }
}
