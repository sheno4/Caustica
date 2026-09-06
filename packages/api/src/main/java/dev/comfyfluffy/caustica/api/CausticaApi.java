package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.session.RenderSessionChannel;

/** Immutable process-scoped rendering capabilities supplied to an extension. */
public record CausticaApi(RenderSessionChannel sessions) {
    public static final String VERSION = "0.8.0";

    public CausticaApi {
        java.util.Objects.requireNonNull(sessions, "sessions");
    }
}
