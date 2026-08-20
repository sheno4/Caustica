package dev.comfyfluffy.caustica.api.provider;

/** Allocates bindless texture slots for one material epoch. */
@FunctionalInterface
public interface TextureRegistrar {
    /**
     * Register a texture and return its epoch-local bindless slot. The returned slot must not be retained
     * across material epochs.
     */
    int register(TextureResource resource);
}
