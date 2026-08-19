package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.ContextualRuntimeFactory;
import dev.comfyfluffy.caustica.api.ResourceId;
import java.util.Objects;

/** A runtime-activation provider factory and the engine-local identity assigned to it by feature registration. */
public record ProviderRegistration<T>(ResourceId id, ContextualRuntimeFactory<? extends T> factory) {
    public ProviderRegistration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(factory, "factory");
    }
}
