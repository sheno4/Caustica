package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.material.MaterialEmissionIndex;

/** Host lifecycle and authored material semantics consumed by the renderer runtime. */
public interface RtRuntimeHost {
    void resetPresentationFailure();

    void resetFrameBridge();

    void destroyUiPresentation();

    MaterialEmissionIndex analyzeMaterialEmission();

    float dielectricIor(ResourceId material);
}
