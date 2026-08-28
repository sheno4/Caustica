package dev.comfyfluffy.caustica.api.scene;

import java.util.Objects;

/** Immutable creation facts for one independently retained scene. */
public record SceneDefinition(SceneEnvironment environment, double metersPerSceneUnit) {
    public SceneDefinition {
        Objects.requireNonNull(environment, "environment");
        if (!Double.isFinite(metersPerSceneUnit) || metersPerSceneUnit <= 0.0) {
            throw new IllegalArgumentException("metersPerSceneUnit must be finite and positive");
        }
    }
}
