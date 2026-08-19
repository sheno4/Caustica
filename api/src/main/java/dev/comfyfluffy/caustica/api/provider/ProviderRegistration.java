package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.ResourceId;

import dev.comfyfluffy.caustica.api.RuntimeFactory;
import java.util.Objects;

/** A runtime-activation provider factory and the engine-local identity assigned to it by feature registration. */
public record ProviderRegistration<T>(ResourceId id, RuntimeFactory<? extends T> factory) {
    public ProviderRegistration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(factory, "factory");
    }
}
