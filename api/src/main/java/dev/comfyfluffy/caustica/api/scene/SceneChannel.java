package dev.comfyfluffy.caustica.api.scene;

/** Session-scoped, thread-safe capability for creating independently retained scenes. */
public interface SceneChannel {
    /**
     * Creates a scene whose stable coordinate scale and initial environment are immediately usable. The
     * returned administration capability is scoped to this render session. Its {@link SceneHandle#id()} may
     * be shared with contributors without sharing the ability to replace or close the scene.
     *
     * <p>The initial environment implementation must belong to this same contribution scope. If creation is
     * rejected, the environment binding's retirement callback remains caller-owned.
     */
    SceneHandle create(SceneDefinition definition);
}
