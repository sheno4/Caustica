package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.session.RenderSessionChannel;

import java.util.Objects;

/**
 * Process-wide extension entry point installed by the host.
 */
public final class CausticaApi {
    public static final String VERSION = "0.4.0";
    private static CausticaApi instance;

    private final RenderSessionChannel sessions;

    private CausticaApi(RenderSessionChannel sessions) {
        this.sessions = sessions;
    }

    /** Host SPI. Extension code registers through {@link #sessions()} and does not call this method. */
    public static synchronized void install(RenderSessionChannel sessions) {
        Objects.requireNonNull(sessions, "sessions");
        if (instance != null) {
            throw new IllegalStateException("Caustica API is already initialized");
        }
        instance = new CausticaApi(sessions);
    }

    /** Returns the process API installed before extension discovery. */
    public static synchronized CausticaApi getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Caustica API has not been initialized by a host adapter");
        }
        return instance;
    }

    /** Registers factories which create fresh contributions for each render session. */
    public RenderSessionChannel sessions() {
        return sessions;
    }
}
