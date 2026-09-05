package dev.comfyfluffy.caustica.settings.testing;

import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionLookup;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsAccess;

import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory writable settings for API and session tests without filesystem dependencies. */
public final class InMemorySettings implements SettingsAccess {
    private record Key(ResourceId feature, Option<?> option) { }
    private Map<Key, Object> values = Map.of();
    private int saves;

    @Override
    public void apply(ResourceId feature, Option<?> option, Object value) {
        Map<Key, Object> updated = new LinkedHashMap<>(values);
        updated.put(new Key(feature, option), option.normalize(value));
        values = Map.copyOf(updated);
    }

    @Override
    public void save() { saves++; }

    public int saves() { return saves; }

    @Override
    public OptionValues options(ResourceId feature) { return snapshot().options(feature); }

    @Override
    public OptionLookup snapshot() {
        Map<Key, Object> snapshot = values;
        return feature -> new OptionValues() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T get(Option<T> option) {
                return (T) snapshot.getOrDefault(new Key(feature, option), option.defaultValue());
            }
        };
    }
}
