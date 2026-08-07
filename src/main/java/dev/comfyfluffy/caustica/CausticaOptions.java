package dev.comfyfluffy.caustica;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;
import dev.comfyfluffy.caustica.api.Feature;
import dev.comfyfluffy.caustica.api.Option;
import dev.comfyfluffy.caustica.api.pass.PassOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * TOML-backed storage for extension-declared {@link Option} values ({@code config/caustica-options.toml}),
 * the concrete store behind every {@link PassOptions} view.
 *
 * <p>Lives next to {@link CausticaConfig} rather than under {@code rt/pass/} because it is not a render
 * pass concern: it holds every registered {@link Feature}'s options, including features that declare no
 * render pass at all (a scene-provider-only extension), and — once feature enable/disable lands — it is
 * what answers "is this extension on?" before the engine decides which passes, providers, and Slang
 * modules to instantiate. Its lifetime is therefore the process's, not the Vulkan device's: it is loaded
 * once from {@code CausticaApi.initialize()} so a settings screen opened from the title menu, before any
 * GPU context exists, reads the same values the renderer will.
 *
 * <p>Deliberately a separate file from {@code config/caustica.toml}: that file's schema is a fixed,
 * hand-curated set of engine settings ({@link CausticaConfig}); this one's key space is whatever the
 * currently-registered extensions declared, so it needs no schema of its own and must never collide with
 * the engine's.
 *
 * <p>Values are keyed by {@code <featureId>.<optionId>}, so one extension's options live under its own
 * feature namespace and can never collide with another's — the point of the option being declared on a
 * {@link Feature} in the first place. Precedence matches {@link CausticaConfig}: a
 * {@code -Dcaustica.option.<namespace>.<path>.<optionId>} system property, then the file, then the
 * {@link Option}'s own declared default.
 *
 * <p>Only {@link Option.Kind#BOOL} and {@link Option.Kind#RANGE} are backed. {@code ENUM} and
 * {@code COLOR} have no consumer yet, and {@link Option} carries no runtime {@code Class<T>} token that
 * would let generic code deserialize an enum value or validate a color int without one — supporting them
 * needs that first.
 */
public final class CausticaOptions {
    private static final String SYSTEM_PROPERTY_PREFIX = "caustica.option.";
    private static final String FILE_NAME = "caustica-options.toml";

    /** Per feature, its declared options by id — built once so a read is a map lookup, not a list scan. */
    private final Map<Identifier, Map<String, Option<?>>> declared;
    private final CommentedFileConfig file;
    /**
     * Immutable and swapped wholesale under {@code synchronized} by {@link #set}, rather than a mutable
     * concurrent map. A frame can then hold the reference it read at {@code beginFrame} and get
     * {@link PassOptions}'s "fixed for the whole frame" guarantee for free — no per-frame defensive copy.
     */
    private volatile Map<String, Object> values;

    private CausticaOptions(Map<Identifier, Map<String, Option<?>>> declared, CommentedFileConfig file,
                            Map<String, Object> values) {
        this.declared = declared;
        this.file = file;
        this.values = values;
    }

    /** Loads from the Fabric config directory. See {@link #load(Path, Map)} to point at another file. */
    public static CausticaOptions load(Map<Identifier, Feature> features) {
        return load(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME), features);
    }

    /** The path is a parameter so a test can point at a temp file without a Fabric runtime present. */
    public static CausticaOptions load(Path path, Map<Identifier, Feature> features) {
        CommentedFileConfig file = CommentedFileConfig.builder(path, TomlFormat.instance())
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .preserveInsertionOrder()
                .sync()
                .build();
        try {
            file.load();
        } catch (Exception e) {
            CausticaMod.LOGGER.warn("Failed to read Caustica options config {}: {}", path, e.toString());
        }
        Map<Identifier, Map<String, Option<?>>> declared = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Feature feature : features.values()) {
            Map<String, Option<?>> byId = new LinkedHashMap<>();
            for (Option<?> option : feature.options()) {
                byId.put(option.id(), option);
                values.put(key(feature.id(), option.id()), resolveInitial(file, feature.id(), option));
            }
            declared.put(feature.id(), Map.copyOf(byId));
        }
        return new CausticaOptions(Map.copyOf(declared), file, Map.copyOf(values));
    }

    /** A live view scoped to one feature's declared options; reads whatever is current at each call. */
    public PassOptions options(Identifier featureId) {
        return view(featureId, values);
    }

    /**
     * A view scoped to one feature reading from {@code snapshot} instead of the current values — how
     * {@code RenderPassManager} serves every pass in a frame from the one map it took at
     * {@code beginFrame}.
     */
    public PassOptions view(Identifier featureId, Map<String, Object> snapshot) {
        Map<String, Option<?>> featureOptions = declared.get(featureId);
        Objects.requireNonNull(featureOptions, () -> "unknown feature " + featureId);
        return new View(featureId, featureOptions, snapshot);
    }

    /** The current values, already immutable — a frame holds this reference for its whole duration. */
    public Map<String, Object> snapshot() {
        return values;
    }

    /**
     * Runtime write path a settings UI calls; persists to disk immediately. {@code rawValue} is whatever
     * the widget produced (a slider's double, a checkbox's boolean) and is coerced and range-clamped
     * against {@code option} before it is stored.
     *
     * <p>Nothing invalidates a pass on a write, so an option a pass only reads at create/resize time
     * (e.g. bloom's level count) takes effect at the next resize rather than immediately.
     */
    public synchronized void set(Identifier featureId, Option<?> option, Object rawValue) {
        Option<?> declaredOption = declaredOption(featureId, option.id());
        if (!declaredOption.equals(option)) {
            throw new IllegalArgumentException(featureId + " declared a different option than the '"
                    + option.id() + "' passed here");
        }
        Object value = coerce(declaredOption, rawValue);
        Map<String, Object> updated = new LinkedHashMap<>(values);
        updated.put(key(featureId, option.id()), value);
        values = Map.copyOf(updated);
        file.set(tomlPath(featureId, option.id()), value);
        file.save();
    }

    private Option<?> declaredOption(Identifier featureId, String optionId) {
        Map<String, Option<?>> featureOptions = declared.get(featureId);
        Objects.requireNonNull(featureOptions, () -> "unknown feature " + featureId);
        Option<?> option = featureOptions.get(optionId);
        if (option == null) {
            throw new IllegalArgumentException(featureId + " has no option " + optionId);
        }
        return option;
    }

    private record View(Identifier featureId, Map<String, Option<?>> declared, Map<String, Object> values)
            implements PassOptions {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Option<T> option) {
            Option<?> found = declared.get(option.id());
            if (found == null) {
                throw new IllegalArgumentException(
                        featureId + " read undeclared option '" + option.id() + "'");
            }
            if (!found.equals(option)) {
                throw new IllegalArgumentException(featureId + " declared a different option than the '"
                        + option.id() + "' read here");
            }
            Object value = values.get(key(featureId, option.id()));
            // Only reachable for a view built over a snapshot that predates the option; the declaration's
            // own default is the answer either way, so no call site ever restates one.
            return value != null ? (T) value : option.defaultValue();
        }
    }

    private static Object resolveInitial(CommentedFileConfig file, Identifier featureId, Option<?> option) {
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
                "options storage does not yet support " + option.kind() + " (" + option.id() + ")");
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
