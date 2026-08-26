package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.scene.SceneProvider;

/** Optional lifecycle and frame-coherence registration for retained scene sources. */
public interface ProviderChannel {
    /**
     * Register a provider for the current runtime activation. Registration begins before this returns;
     * frame callbacks may follow immediately.
     *
     * @throws IllegalArgumentException if this provider instance is already registered
     */
    void add(SceneProvider provider);

    /**
     * Stop a provider and remove it from future notifications. {@link ProviderLifecycle#stop()} finishes
     * before this returns; {@link ProviderLifecycle#shutdown()} runs asynchronously after accepted
     * submissions and their retirement callbacks can no longer reach the instance. The provider instance
     * is not reusable.
     *
     * @throws IllegalArgumentException if this provider is not registered
     */
    void remove(SceneProvider provider);
}
