package dev.comfyfluffy.caustica.api.provider;

/**
 * Session-scoped registration for frame-coherent scene sources. Each provider's callbacks are serialized
 * on the renderer's frame-collection thread and return before tracing begins. If a callback throws, the
 * host reports it and removes that provider; submissions accepted before the exception remain accepted.
 */
public interface SceneProviderChannel {
    /**
     * Registers a provider in the current session scope. Frame callbacks may follow after this call
     * returns. At teardown the host first quiesces callbacks, invokes the contribution's stop callback,
     * and then removes registrations during scoped cleanup.
     *
     * @return an idempotent handle for optional early removal
     * @throws IllegalArgumentException if this provider instance is already registered
     */
    SceneProviderRegistration add(SceneProvider provider);
}
