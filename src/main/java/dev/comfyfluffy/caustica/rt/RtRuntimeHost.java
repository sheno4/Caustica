package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.api.provider.MaterialRule;
import dev.comfyfluffy.caustica.engine.material.MaterialCatalog;

import java.util.List;

/** Host lifecycle and authored material semantics consumed by the renderer runtime. */
public interface RtRuntimeHost {
    void resetPresentationFailure();

    void resetFrameBridge();

    void destroyUiPresentation();

    void resetSceneTextures();

    MaterialCatalog materialCatalog(List<MaterialRule> rules);
}
