package dev.comfyfluffy.caustica.api.scene;

/** Session-scoped, thread-safe capability for creating independently retained scenes. */
public interface SceneOwner {
    /**
     * Creates a scene whose initial environment is immediately usable. The returned ownership capability
     * is scoped to this render session. Its {@link OwnedScene#id()} may be shared with contributors, but
     * only the holder of the {@link OwnedScene} may replace the environment or close the scene.
     */
    OwnedScene createScene(EnvironmentId environment, long parameters);
}
