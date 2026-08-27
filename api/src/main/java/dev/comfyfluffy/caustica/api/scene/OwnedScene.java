package dev.comfyfluffy.caustica.api.scene;

/**
 * Exclusive administration capability for a scene. {@link #id()} returns the separate, non-owning
 * reference shared with cameras and contributors; receiving that reference does not grant this capability.
 */
public interface OwnedScene extends AutoCloseable {
    /**
     * Non-owning identity of the scene administered by this capability. The returned object does not
     * implement {@code OwnedScene}.
     */
    SceneId id();

    /**
     * Replaces this scene's environment. {@code retiredPrevious} runs after no submitted GPU work can
     * select or read the displaced environment parameters. The operation is thread-safe, accepted
     * synchronously, and becomes visible at a renderer update boundary.
     */
    void setEnvironment(EnvironmentId environment, long parameters, Runnable retiredPrevious);

    /**
     * Releases ownership and cascades removal of the scene's placements and lights. {@code retired} runs
     * after the scene and all submitted uses have retired. The owning session scope also performs this
     * operation automatically if it was not called explicitly. After this call, every copy of this
     * {@link #id()} reference is stale and submissions naming it are rejected.
     */
    void close(Runnable retired);

    /** Releases the scene without requesting a retirement notification. */
    @Override
    default void close() {
        close(() -> { });
    }
}
