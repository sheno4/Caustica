package dev.comfyfluffy.caustica.api.provider;

import java.util.Objects;

/** A stable, engine-owned name assigned to a provider when its feature registers it. */
public record ProviderId(String namespace, String path) {
    public ProviderId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (namespace.isBlank() || path.isBlank()) {
            throw new IllegalArgumentException("provider id namespace and path must not be blank");
        }
    }

    public static ProviderId of(String namespace, String path) {
        return new ProviderId(namespace, path);
    }

    @Override
    public String toString() {
        return namespace + ':' + path;
    }
}
