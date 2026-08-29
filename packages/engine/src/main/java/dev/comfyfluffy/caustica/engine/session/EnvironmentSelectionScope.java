package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;

/** Owner-scoped environment selection and retirement controls for one fixed scene. */
public interface EnvironmentSelectionScope {
    void select(EnvironmentBinding<?> binding);
    void invalidate();
    void drain();
}
