package dev.comfyfluffy.caustica.api.provider;

import java.io.IOException;

/** Opens all images behind one canonical texture as one lifetime, without intermediate image copies. */
@FunctionalInterface
public interface MaterialTextureSource {
    MaterialTextureImage open() throws IOException;

    /** Exhaustive alpha frames for this epoch, or zero when no conservative temporal claim is available. */
    default int alphaFrameCount() {
        return 0;
    }
}
