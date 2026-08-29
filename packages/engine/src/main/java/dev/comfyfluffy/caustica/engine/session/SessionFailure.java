package dev.comfyfluffy.caustica.engine.session;

import java.util.Objects;

/** One isolated contribution failure observed while opening or tearing down a render session. */
public record SessionFailure(ContributionOwner owner, Stage stage, Throwable cause) {
    public SessionFailure {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(cause, "cause");
    }

    public enum Stage {
        CREATE_SCOPE,
        OPEN_CONTRIBUTION,
        QUIESCE,
        STOP_CONTRIBUTION,
        INVALIDATE,
        DRAIN,
        CLOSE_CONTRIBUTION,
        CLOSE_SCOPE
    }
}
