package dev.comfyfluffy.caustica.spi.host;

import dev.comfyfluffy.caustica.api.provider.MaterialRule;
import dev.comfyfluffy.caustica.engine.material.MaterialCatalog;

import java.util.List;

/**
 * Integration contract implemented by the application hosting the renderer runtime.
 * This SPI controls host lifecycle and authored material semantics; it is not part of the supported extension API.
 */
public interface RuntimeHost {
    void resetPresentationFailure();

    void resetFrameBridge();

    void destroyUiPresentation();

    void resetSceneTextures();

    MaterialCatalog materialCatalog(List<MaterialRule> rules);
}
