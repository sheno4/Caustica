package dev.comfyfluffy.caustica.rt.pass;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.Feature;
import dev.comfyfluffy.caustica.api.Option;
import dev.comfyfluffy.caustica.api.pass.PassOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TOML-backed storage for extension-declared {@link Option} values ({@code config/caustica-options.toml}),
 * the concrete backing {@link PassOptions} was designed against but did not have yet (see that interface's
 * javadoc). Deliberately a separate file from {@code config/caustica.toml}: that file's schema is a fixed,
 * hand-curated set of engine settings ({@code CausticaConfig}); this one's key space is whatever the
 * currently-registered extensions declared, so it needs no schema of its own and must never collide with
 * the engine's.
 *
 * <p>Values are keyed by {@code <featureId>.<optionId>}, so one extension's options live under its own
 * feature namespace and can never collide with another's — the point of the option being declared on a
 * {@link Feature} in the first place. Precedence matches {@code CausticaConfig}: a
 * {@code -Dcaustica.pass.<namespace>.<path>.<optionId>} system property, then the file, then the
 * {@link Option}'s own declared default.
 *
 * <p>Only {@link Option.Kind#BOOL} and {@link Option.Kind#RANGE} are backed. {@code ENUM} and
 * {@code COLOR} have no consumer yet, and {@link Option} carries no runtime {@code Class<T>} token that
 * would let generic code deserialize an enum value or validate a color int without one — supporting them
 * needs that first.
 */
public final class PassOptionsStore {
    private static final String SYSTEM_PROPERTY_PREFIX = "caustica.pass.";

    private final Map<Identifier, Feature> features;
    private final CommentedFileConfig file;
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    private PassOptionsStore(Map<Identifier, Feature> features, CommentedFileConfig file) {
        this.features = features;
        this.file = file;
        for (Feature feature : features.values()) {
            for (Option<?> option : feature.options()) {
                values.put(key(feature.id(), option.id()), resolveInitial(feature.id(), option));
            }
        }
    }

    public static PassOptionsStore load(Map<Identifier, Feature> features) {
        Path path = resolveConfigPath();
        CommentedFileConfig file = CommentedFileConfig.builder(path, TomlFormat.instance())
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .preserveInsertionOrder()
                .sync()
                .build();
        try {
            file.load();
        } catch (Exception e) {
            CausticaMod.LOGGER.warn("Failed to read Caustica pass-options config {}: {}", path, e.toString());
        }
        return new PassOptionsStore(Map.copyOf(features), file);
    }

    private static Path resolveConfigPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("caustica-options.toml");
        } catch (Throwable t) {
            return Path.of("config", "caustica-options.toml");
        }
    }

    /**
     * A live view scoped to one feature's declared options. {@link PassOptions#get} throws on an option id
     * the feature never declared, so a typo at the call site fails loudly instead of silently returning
     * whatever fallback the caller happened to pass.
     */
    public PassOptions options(Identifier featureId) {
        Feature feature = features.get(featureId);
        Objects.requireNonNull(feature, () -> "unknown feature " + featureId);
        return viewOf(feature, values);
    }

    /** An immutable copy of every current value, frozen for one frame — see {@link PassOptions}'s javadoc. */
    public Map<String, Object> snapshotValues() {
        return Map.copyOf(values);
    }

    static PassOptions viewOf(Feature feature, Map<String, Object> values) {
        return new FeatureOptions(feature, values);
    }

    private record FeatureOptions(Feature feature, Map<String, Object> values) implements PassOptions {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(String optionId, T fallback) {
            boolean declared = feature.options().stream().anyMatch(option -> option.id().equals(optionId));
            if (!declared) {
                throw new IllegalArgumentException(
                        feature.id() + " read undeclared option '" + optionId + "'");
            }
            Object value = values.get(key(feature.id(), optionId));
            return value != null ? (T) value : fallback;
        }
    }

    /** Runtime write path a future settings UI would call; persists to disk immediately. */
    public synchronized void set(Identifier featureId, String optionId, Object rawValue) {
        Feature feature = features.get(featureId);
        Objects.requireNonNull(feature, () -> "unknown feature " + featureId);
        Option<?> option = feature.options().stream().filter(candidate -> candidate.id().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(featureId + " has no option " + optionId));
        Object value = coerce(option, rawValue);
        values.put(key(featureId, optionId), value);
        file.set(tomlPath(featureId, optionId), value);
        file.save();
    }

    private Object resolveInitial(Identifier featureId, Option<?> option) {
        String property = System.getProperty(systemPropertyKey(featureId, option.id()));
        if (property != null) {
            Object parsed = parse(option, property);
            if (parsed != null) {
                return parsed;
            }
        }
        String tomlPath = tomlPath(featureId, option.id());
        if (file.contains(tomlPath)) {
            return coerce(option, file.<Object>get(tomlPath));
        }
        return option.defaultValue();
    }

    private static Object parse(Option<?> option, String raw) {
        try {
            return coerce(option, switch (option.kind()) {
                case BOOL -> Boolean.parseBoolean(raw.trim());
                case RANGE -> Float.parseFloat(raw.trim());
                default -> throw unsupported(option);
            });
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Object coerce(Option<?> option, Object raw) {
        return switch (option.kind()) {
            case BOOL -> (Boolean) raw;
            case RANGE -> (float) Math.clamp(((Number) raw).doubleValue(), option.minimum(), option.maximum());
            default -> throw unsupported(option);
        };
    }

    private static UnsupportedOperationException unsupported(Option<?> option) {
        return new UnsupportedOperationException(
                "pass-options storage does not yet support " + option.kind() + " (" + option.id() + ")");
    }

    private static String key(Identifier featureId, String optionId) {
        return featureId + "." + optionId;
    }

    private static String tomlPath(Identifier featureId, String optionId) {
        return featureId.getNamespace() + ":" + featureId.getPath() + "." + optionId;
    }

    private static String systemPropertyKey(Identifier featureId, String optionId) {
        return SYSTEM_PROPERTY_PREFIX + featureId.getNamespace() + "." + featureId.getPath() + "." + optionId;
    }
}
