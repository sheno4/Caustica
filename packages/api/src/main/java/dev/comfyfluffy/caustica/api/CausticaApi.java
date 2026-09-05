package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.session.RenderSessionChannel;
import dev.comfyfluffy.caustica.settings.SettingsAccess;

/**
 * Immutable process-scoped capabilities supplied to an extension after every settings declaration has
 * been registered and its persisted values have been loaded.
 */
public record CausticaApi(RenderSessionChannel sessions, SettingsAccess options) {
    public static final String VERSION = "0.8.0";

    public CausticaApi {
        java.util.Objects.requireNonNull(sessions, "sessions");
        java.util.Objects.requireNonNull(options, "options");
    }
}
