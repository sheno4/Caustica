package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.session.EnvironmentSelectionScope;

/** Owner-scoped environment mutation for one fixed host-owned scene. */
public final class SceneEnvironmentContributionChannel implements EnvironmentSelectionScope {
    final SceneDirectory directory;
    final SceneId scene;
    boolean accepting = true;

    SceneEnvironmentContributionChannel(
            SceneDirectory directory, SceneId scene) {
        this.directory = directory;
        this.scene = scene;
    }

    @Override public void select(EnvironmentBinding<?> binding) {
        directory.selectEnvironment(this, binding);
    }
    public void invalidate() { directory.invalidate(this); }
    public void drain() { directory.drain(this); }
}
