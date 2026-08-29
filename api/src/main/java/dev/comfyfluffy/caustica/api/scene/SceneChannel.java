package dev.comfyfluffy.caustica.api.scene;

/** Session-scoped, thread-safe capability for creating independently retained scenes. */
public interface SceneChannel {
    /**
     * Creates a scene whose stable coordinate scale and initial environment are immediately usable. The
     * returned administration capability is scoped to this render session. Its {@link SceneHandle#id()} may
     * be shared with geometry, lights, and views without sharing the ability to replace or close the scene.
     *
     * <p>The initial environment implementation may be shared by another contribution in this render session.
     * If creation is rejected, the environment binding's retirement callback remains caller-owned.
     *
     * @throws IllegalArgumentException if the environment id belongs to another render session or its
     *         binding data carries a different schema token
     */
    SceneHandle create(SceneDefinition definition);
}
