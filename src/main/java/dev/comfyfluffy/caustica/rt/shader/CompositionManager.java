package dev.comfyfluffy.caustica.rt.shader;

import java.util.Objects;

/** Atomically publishes validated compositions and tracks the previous publication until retirement. */
public final class CompositionManager {
    private Composition active;
    private Composition retiring;

    public CompositionManager(Composition initial) {
        active = Objects.requireNonNull(initial, "initial");
    }

    public synchronized Composition current() {
        return active;
    }

    public synchronized Composition retiringOrNull() {
        return retiring;
    }

    public synchronized Composition activate(Composition candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (retiring != null) {
            throw new IllegalStateException("composition " + retiring.contentHash()
                    + " has not finished retiring");
        }
        retiring = active;
        active = candidate;
        return candidate;
    }

    public synchronized void retireCompleted() {
        retiring = null;
    }
}
