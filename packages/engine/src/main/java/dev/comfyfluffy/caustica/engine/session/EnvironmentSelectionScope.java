package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;

/**
 * One owner's environment selection slot for a fixed scene.
 *
 * <p>Each successful selection replaces this scope's slot and gives it precedence over every other
 * surviving slot. Invalidating the scope removes its slot and restores the surviving selection which
 * most recently had precedence. Binding retirement waits for both slot removal and every published GPU
 * snapshot which can still reference the binding.</p>
 */
public interface EnvironmentSelectionScope {
    void select(EnvironmentBinding<?> binding);
    void invalidate();
    void drain();
}
