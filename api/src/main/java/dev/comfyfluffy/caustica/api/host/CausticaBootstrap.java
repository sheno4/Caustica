package dev.comfyfluffy.caustica.api.host;

import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.session.RenderSessionChannel;

/** Creates extension API capabilities for a host adapter. */
public final class CausticaBootstrap {
    private CausticaBootstrap() {
    }

    /** Creates the immutable process capabilities supplied directly to discovered extensions. */
    public static CausticaApi create(RenderSessionChannel sessions) {
        return new CausticaApi(sessions);
    }
}
