package dev.comfyfluffy.caustica.api.host;

import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.session.RenderSessionChannel;

/** Host-adapter bootstrap kept separate from the extension-facing API entry point. */
public final class CausticaBootstrap {
    private CausticaBootstrap() {
    }

    /** Installs process-time session-factory registration before invoking any extension. */
    public static void install(RenderSessionChannel sessions) {
        CausticaApi.install(sessions);
    }
}
