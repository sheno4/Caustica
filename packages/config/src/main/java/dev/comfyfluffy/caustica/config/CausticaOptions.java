package dev.comfyfluffy.caustica.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;
import dev.comfyfluffy.caustica.settings.FeatureSettings;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionLookup;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsAccess;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One process-wide preference store. Immutable effective snapshots include latched process overrides. */
public final class CausticaOptions implements SettingsAccess {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");
    private final CommentedFileConfig file;
    private final Map<Key, Object> preferences = new LinkedHashMap<>();
    private final Map<Key, Object> overrides = new LinkedHashMap<>();
    private final Map<List<String>, Optional<Object>> pending = new LinkedHashMap<>();
    private volatile State state = new State(Map.of(), Map.of());

    private record Key(ResourceId feature, String option) { }

    /** Declarations and effective values are published together so every snapshot is self-contained. */
    private record State(Map<ResourceId, Map<String, Option<?>>> declared, Map<Key, Object> values)
            implements OptionLookup {
        @Override
        public OptionValues options(ResourceId feature) {
            Map<String, Option<?>> options = Objects.requireNonNull(declared.get(feature),
                    () -> "unknown feature " + feature);
            return new View(feature, options, values);
        }
    }

    private CausticaOptions(CommentedFileConfig file) {
        this.file = file;
    }

    public static CausticaOptions load(Path path, SettingsRegistry registry) {
        CausticaOptions store = new CausticaOptions(open(path));
        store.register(registry);
        return store;
    }

    private static CommentedFileConfig open(Path path) {
        CommentedFileConfig file = CommentedFileConfig.builder(path, TomlFormat.instance())
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY).preserveInsertionOrder().sync().build();
        try {
            file.load();
        } catch (Exception failure) {
            LOGGER.warn("Failed to read Caustica config {}: {}", path, failure.toString());
        }
        return file;
    }

    /** Adds declarations to the loaded store, retaining edits and overrides of existing features. */
    public synchronized void register(SettingsRegistry registry) {
        for (FeatureSettings feature : registry.all()) register(feature);
    }

    public synchronized void register(FeatureSettings feature) {
        Map<String, Option<?>> existing = state.declared().get(feature.id());
        Map<String, Option<?>> options = new LinkedHashMap<>();
        for (Option<?> option : feature.options()) options.put(option.id(), option);
        if (existing != null) {
            if (!existing.equals(options)) throw new IllegalArgumentException("different settings for " + feature.id());
            return;
        }
        Map<ResourceId, Map<String, Option<?>>> declared = new LinkedHashMap<>(state.declared());
        declared.put(feature.id(), Map.copyOf(options));
        for (Option<?> option : feature.options()) {
            Key key = new Key(feature.id(), option.id());
            List<String> path = tomlPath(feature.id(), option);
            Object preference = option.defaultValue();
            if (file.contains(path)) preference = decode(option, file.get(path)).orElse(preference);
            preferences.put(key, preference);
            String property = System.getProperty(systemPropertyKey(feature.id(), option));
            if (property != null) decode(option, property).ifPresent(value -> overrides.put(key, value));
        }
        publish(declared);
    }

    private static Optional<Object> decode(Option<?> option, Object raw) {
        try {
            return Optional.of(option.normalize(raw));
        } catch (IllegalArgumentException | ClassCastException failure) {
            LOGGER.warn("Ignoring invalid value for Caustica option {}: {}", option.id(), raw);
            return Optional.empty();
        }
    }

    private void publish(Map<ResourceId, Map<String, Option<?>>> declared) {
        Map<Key, Object> effective = new LinkedHashMap<>(preferences);
        effective.putAll(overrides);
        state = new State(Map.copyOf(declared), Map.copyOf(effective));
    }

    @Override
    public OptionValues options(ResourceId featureId) {
        return state.options(featureId);
    }

    @Override
    public OptionLookup snapshot() {
        return state;
    }

    /** Changes the preference; a process override remains effective until process restart. */
    @Override
    public synchronized void apply(ResourceId featureId, Option<?> option, Object rawValue) {
        requireOption(featureId, option);
        Object value = option.normalize(rawValue);
        preferences.put(new Key(featureId, option.id()), value);
        pending.put(tomlPath(featureId, option), option.encode(value));
        publish(state.declared());
    }

    @Override
    public synchronized void set(ResourceId featureId, Option<?> option, Object rawValue) {
        apply(featureId, option, rawValue);
        save();
    }

    @Override
    public synchronized void save() {
        if (pending.isEmpty()) return;
        pending.forEach((path, value) -> {
            if (value.isPresent()) file.set(path, value.get());
            else file.remove(path);
        });
        file.save();
        pending.clear();
    }

    public synchronized boolean overridden(ResourceId featureId, Option<?> option) {
        requireOption(featureId, option);
        return overrides.containsKey(new Key(featureId, option.id()));
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> T preference(ResourceId featureId, Option<T> option) {
        requireOption(featureId, option);
        return (T) preferences.get(new Key(featureId, option.id()));
    }

    /** Imports missing extension preferences without replacing canonical values or unsaved edits. */
    public synchronized void importLegacy(Path path) {
        if (!Files.exists(path)) return;
        try (CommentedFileConfig legacy = open(path)) {
            state.declared().forEach((feature, options) -> options.values().forEach(option -> {
                List<String> destination = tomlPath(feature, option);
                String source = feature + "." + option.id();
                if (!file.contains(destination) && !pending.containsKey(destination) && legacy.contains(source)) {
                    decode(option, legacy.get(source)).ifPresent(value -> apply(feature, option, value));
                }
            }));
        }
        save();
    }

    private void requireOption(ResourceId feature, Option<?> option) {
        Map<String, Option<?>> declared = Objects.requireNonNull(state.declared().get(feature),
                () -> "unknown feature " + feature);
        requireToken(feature, declared, option);
    }

    private static void requireToken(ResourceId feature, Map<String, Option<?>> declared, Option<?> option) {
        Option<?> found = declared.get(option.id());
        if (found == null) throw new IllegalArgumentException(feature + " read undeclared option '" + option.id() + "'");
        if (!found.equals(option)) throw new IllegalArgumentException(feature + " declared a different option '" + option.id() + "'");
    }

    private record View(ResourceId feature, Map<String, Option<?>> declared, Map<Key, Object> values)
            implements OptionValues {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Option<T> option) {
            requireToken(feature, declared, option);
            return (T) values.get(new Key(feature, option.id()));
        }
    }

    private static List<String> tomlPath(ResourceId feature, Option<?> option) {
        if (option.tomlPath() != null) return List.of(option.tomlPath().split("\\."));
        // Feature ids are literal table names; dots in option ids create tables within that feature.
        List<String> path = new ArrayList<>();
        path.add(feature.toString());
        path.addAll(List.of(option.id().split("\\.")));
        return List.copyOf(path);
    }

    private static String systemPropertyKey(ResourceId feature, Option<?> option) {
        return option.systemPropertyKey() != null ? option.systemPropertyKey()
                : "caustica.option." + feature.namespace() + "." + feature.path() + "." + option.id();
    }
}
