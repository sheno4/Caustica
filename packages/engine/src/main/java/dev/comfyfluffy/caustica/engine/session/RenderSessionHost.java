package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.CausticaApi;

import java.util.Objects;

/** Process-scoped extension registration and creation of renderer-owned session controllers. */
public final class RenderSessionHost implements AutoCloseable {
    private final EngineRenderSessionChannel sessions = new EngineRenderSessionChannel();
    private final CausticaApi api;

    public RenderSessionHost() {
        api = new CausticaApi(sessions);
    }

    /** Immutable process capability passed to discovered extensions. */
    public CausticaApi api() {
        return api;
    }

    /** Opens a controller for one live renderer session. */
    public EngineRenderSession openSession(ContributionScopeFactory scopes, SessionFailureHandler failures) {
        Objects.requireNonNull(scopes, "scopes");
        Objects.requireNonNull(failures, "failures");
        return sessions.openSession(scopes, failures);
    }

    /** Prevents future registration and session creation. Live sessions retain their explicit close boundary. */
    @Override
    public void close() {
        sessions.close();
    }
}
