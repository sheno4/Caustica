package dev.comfyfluffy.caustica.api.scene;

/**
 * Administration capability for one session-owned scene. {@link #id()} returns the separate, non-owning
 * reference shared with views and retained scene content; receiving that reference does not grant this
 * capability.
 * The session guarantees cleanup; closing this handle requests earlier removal.
 */
public interface SceneHandle extends AutoCloseable {
    /**
     * Non-owning identity of the scene administered by this capability. The returned object does not
     * implement {@code SceneHandle}.
     */
    SceneId id();

    /**
     * Replaces this scene's environment binding. The displaced binding's own retirement callback runs
     * after no submitted GPU work can select or read it. The operation is thread-safe, accepted
     * synchronously, and becomes visible at a renderer update boundary. A rejected replacement does not
     * take ownership of the new binding's callback. The environment implementation may be shared by another
     * contribution in this render session; the handle remains the authority to mutate this scene.
     *
     * @throws IllegalStateException if this scene has already closed
     * @throws IllegalArgumentException if the environment id belongs to another render session or its
     *         binding data carries a different schema token
     */
    void setEnvironment(EnvironmentBinding<?> environment);

    /**
     * Requests early removal and cascades removal of the scene's current placements and lights.
     * Scene-independent meshes remain retained, and issued instance/light ids remain valid for later reuse
     * in another live scene. The session performs the same cleanup automatically if this method was not
     * called. Idempotent; after this call, every copy of this {@link #id()} reference is stale and
     * submissions naming that scene are rejected.
     */
    @Override
    void close();
}
