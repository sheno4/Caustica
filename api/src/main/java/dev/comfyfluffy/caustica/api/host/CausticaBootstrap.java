package dev.comfyfluffy.caustica.api.host;

import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.session.RenderSessionChannel;

/** Host-adapter bootstrap kept separate from the extension-facing API entry point. */
public final class CausticaBootstrap {
    private CausticaBootstrap() {
    }

    /** Creates the immutable process capabilities supplied directly to discovered extensions. */
    public static CausticaApi create(RenderSessionChannel sessions) {
        return new CausticaApi(sessions);
    }
}
