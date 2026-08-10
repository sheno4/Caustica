package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.Objects;

/** A provider instance and the engine-local identity assigned to it by feature registration. */
public record ProviderRegistration<T>(ResourceId id, T provider) {
    public ProviderRegistration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
    }
}
