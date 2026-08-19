package dev.comfyfluffy.caustica.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Mutable state shared by all runtime contributions of one active feature. A new context is created for
 * each runtime activation and is never shared between features or sessions.
 */
public final class FeatureRuntimeContext {
    private final ResourceId featureId;
    private final Map<ResourceId, Object> values = new LinkedHashMap<>();

    FeatureRuntimeContext(ResourceId featureId) {
        this.featureId = Objects.requireNonNull(featureId, "featureId");
    }

    public ResourceId featureId() {
        return featureId;
    }

    /** Return the value for {@code key}, creating and retaining it once for this activation if absent. */
    public synchronized <T> T getOrCreate(Key<T> key, Supplier<? extends T> factory) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");
        Object value = values.get(key.id());
        if (value == null) {
            value = Objects.requireNonNull(factory.get(), "factory created a null context value for " + key.id());
            values.put(key.id(), value);
        } else if (!key.type().isInstance(value)) {
            throw new IllegalStateException("context key " + key.id() + " already stores "
                    + value.getClass().getName() + ", not " + key.type().getName());
        }
        return key.type().cast(value);
    }

    /** A feature-local, runtime-typed identity for one shared activation value. */
    public record Key<T>(ResourceId id, Class<T> type) {
        public Key {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
        }
    }
}
