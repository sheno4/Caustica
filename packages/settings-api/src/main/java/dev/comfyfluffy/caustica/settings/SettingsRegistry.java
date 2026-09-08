package dev.comfyfluffy.caustica.settings;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Feature settings in declaration order. Host settings screens use this registry independently of the
 * renderer.
 */
public final class SettingsRegistry {
    private final Map<ResourceId, FeatureSettings> features = new LinkedHashMap<>();

    public SettingsBuilder feature(ResourceId id) {
        Objects.requireNonNull(id, "id");
        return new SettingsBuilder(this, id);
    }

    synchronized void register(FeatureSettings settings) {
        if (features.putIfAbsent(settings.id(), settings) != null) {
            throw new IllegalStateException("duplicate feature settings for " + settings.id());
        }
    }

    /** @throws IllegalArgumentException if no feature declared settings under this id */
    public synchronized FeatureSettings settings(ResourceId id) {
        FeatureSettings settings = features.get(id);
        if (settings == null) {
            throw new IllegalArgumentException("no settings registered for " + id);
        }
        return settings;
    }

    public synchronized boolean declared(ResourceId id) {
        return features.containsKey(id);
    }

    /** Declaration order, which is the order a settings screen shows features within a category. */
    public synchronized Collection<FeatureSettings> all() {
        return List.copyOf(features.values());
    }
}
