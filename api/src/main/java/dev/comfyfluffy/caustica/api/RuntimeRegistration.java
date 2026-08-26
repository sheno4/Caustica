package dev.comfyfluffy.caustica.api;

import java.util.Objects;

/**
 * One runtime contribution a feature declares: a process-stable identity and the factory that builds an
 * instance for a runtime activation.
 *
 * <p>The same shape for every kind — world-resource pass, post-effect pass, UI pass, scene provider, light
 * provider, material source — because the renderer treats them the same way at registration time. What
 * kind it is, and therefore where in the frame it runs, is {@code T}; there is no stage or category field.
 */
public record RuntimeRegistration<T>(ResourceId id, ContextualRuntimeFactory<? extends T> factory) {
    public RuntimeRegistration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(factory, "factory");
    }
}
