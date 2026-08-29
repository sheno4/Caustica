package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Cross-thread mailbox for retained GPU completions consumed by the session-control thread. */
final class RetainedSceneProgressQueue<T> {
    private final ConcurrentLinkedQueue<T> completed = new ConcurrentLinkedQueue<>();
    private volatile Runnable progressAvailable = () -> { };

    void onProgressAvailable(Runnable wakeup) {
        progressAvailable = Objects.requireNonNull(wakeup, "wakeup");
        if (!completed.isEmpty()) wakeup.run();
    }

    void add(T completion) {
        completed.add(Objects.requireNonNull(completion, "completion"));
        progressAvailable.run();
    }

    T poll() {
        return completed.poll();
    }
}
