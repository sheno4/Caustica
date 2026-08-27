package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.scene.SceneProvider;

/**
 * Session-scoped registration for frame-coherent scene sources. Each provider's callbacks are serialized
 * on the renderer's frame-collection thread and return before tracing begins. If a callback throws, the
 * host reports it and removes that provider; submissions accepted before the exception remain accepted.
 */
public interface ProviderChannel {
    /**
     * Registers a provider in the current session scope. Frame callbacks may follow after this call
     * returns. The host automatically removes it before the contribution's stop callback.
     *
     * @throws IllegalArgumentException if this provider instance is already registered
     */
    void add(SceneProvider provider);

    /**
     * Removes a provider early. This waits for an in-progress callback; no callback is running and none can
     * begin when the method returns. A provider must not remove itself from inside its callback.
     */
    void remove(SceneProvider provider);
}
