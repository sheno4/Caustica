package dev.comfyfluffy.caustica.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central mutable runtime configuration. Each setting resolves its value, in order of precedence, from a
 * {@code -Dcaustica.*} system property, then the {@code config/caustica.toml} file, then a hardcoded
 * default. The settings UI and any other code call the same {@code set(...)} methods, and {@link #save()}
 * writes the current values back to the TOML file.
 *
 * <p>The system property namespace ({@code caustica.rt.foo}) and the TOML layout are independent: the file
 * uses real nested tables (e.g. {@code [omm]} with a {@code subdivision} key) grouped for readability, while
 * the property namespace stays flat and dotted for convenient one-off {@code -D} overrides.
 */
public final class CausticaConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");
    private static final List<RuntimeSetting<?>> SETTINGS = new CopyOnWriteArrayList<>();

    private static Path configPath;
    private static CommentedFileConfig file;

    private CausticaConfig() {
    }

    public static List<RuntimeSetting<?>> settings() {
        return List.copyOf(SETTINGS);
    }

    public static Path configPath() {
        return configPath != null ? configPath : Path.of("config").resolve("caustica.toml");
    }

    /** Selects the host's config directory before settings are registered. */
    public static synchronized void configure(Path configDirectory) {
        if (file != null || !SETTINGS.isEmpty()) {
            throw new IllegalStateException("Caustica config is already initialized");
        }
        configPath = configDirectory.resolve("caustica.toml");
    }

    public static void reloadFromSystemProperties() {
        for (RuntimeSetting<?> setting : SETTINGS) {
            setting.reloadFromSystemProperties();
        }
    }

    /**
     * Every class holding settings, in the order their keys should appear in the file and their rows in the
     * settings screen. Listed explicitly rather than discovered by reflection because
     * {@code getDeclaredClasses()} has no specified order, and this order is observable in both places.
     * {@code CausticaConfigTest} fails the build if a holder is missing.
     */
    static final List<Class<?>> HOLDERS = List.of(
            Rt.class, Rt.Composite.class, Rt.Terrain.class, Rt.Lights.class,
            Rt.Entities.class, Rt.Overlay.class, Rt.Denoising.class, Rt.DlssRr.class, Rt.DlssSr.class,
            Rt.Fg.class, Rt.Reflex.class, Rt.Exposure.class, Rt.Tonemap.class, Rt.FrameStats.class,
            Rt.Screenshots.class, Rt.Hdr.class, Rt.Composition.class,
            Ngx.class, Slang.class);

    /**
     * Forces every settings holder to class-initialize so all settings are registered (and have applied
     * their file values). Call before {@link #save()} to write a complete file, and once at startup so the
     * file round-trips the full surface even for settings the renderer has not touched yet.
     */
    public static void ensureRegistered() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        for (Class<?> holder : HOLDERS) {
            try {
                lookup.ensureInitialized(holder);
            } catch (IllegalAccessException e) {
                throw new AssertionError("holder is not a nest mate of CausticaConfig: " + holder, e);
            }
        }
    }

    /** Writes the default config file if it does not exist yet. */
    public static void saveIfMissing() {
        ensureRegistered();
        if (file().valueMap().isEmpty()) {
            save();
        }
    }

    /** Serializes all registered settings to the TOML config file. */
    public static synchronized void save() {
        ensureRegistered();
        writeComments();
        for (RuntimeSetting<?> setting : SETTINGS) {
            setting.writeToFile(file());
        }
        file().save();
    }

    private static void writeComments() {
        CommentedFileConfig file = file();
        file.setComment("enabled",
                " Caustica ray-tracing settings. A matching -Dcaustica.* system property overrides a value here.");
        file.setComment("terrain",
                " Controls terrain loading. Higher limits can load terrain faster but use more CPU and GPU time.");
        file.setComment("frame-generation",
                " DLSS Frame Generation. Requires supported NVIDIA hardware and drivers.");
        file.setComment("reflex",
                " NVIDIA Reflex. Requires supported NVIDIA hardware and drivers.\n"
                        + " minimum-interval-us controls frame limiting; 0 disables the limit.");
        file.setComment("lights",
                " Controls how Minecraft emitter geometry is converted into light-provider descriptors.");
        file.setComment("tonemap",
                " Controls the final image. gamma: 1 is neutral; lower values brighten midtones.");
        file.setComment("exposure",
                " Controls automatic exposure. manual-ev sets exposure in manual mode and adjusts it in auto mode.\n"
                        + " adapt-darken and adapt-brighten control adjustment speed in seconds.\n"
                        + " sky-weight-cap and emissive-weight-cap limit how much bright areas affect exposure.");
        file.setComment("hdr",
                " HDR display output. Requires operating system and display support.\n"
                        + " ui-nits controls UI brightness; peak-nits must be 500, 1000, 2000, or 4000.");
        file.setComment("screenshots",
                " exr-enabled saves an ACEScg EXR beside the normal F2 PNG while ray tracing is active.");
    }

    private static synchronized CommentedFileConfig file() {
        if (file == null) {
            file = loadFile(configPath());
        }
        return file;
    }

    private static CommentedFileConfig loadFile(Path path) {
        CommentedFileConfig config = CommentedFileConfig.builder(path, TomlFormat.instance())
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .preserveInsertionOrder()
                .sync()
                .build();
        try {
            config.load();
        } catch (Exception e) {
            LOGGER.warn("Failed to read Caustica config {}: {}", path, e.toString());
        }
        return config;
    }

    private static Boolean fileBoolean(String tomlPath) {
        return file().contains(tomlPath) ? file().<Boolean>get(tomlPath) : null;
    }

    private static Number fileNumber(String tomlPath) {
        return file().contains(tomlPath) ? file().<Number>get(tomlPath) : null;
    }

    private static String fileString(String tomlPath) {
        return file().contains(tomlPath) ? file().<String>get(tomlPath) : null;
    }

    public interface RuntimeSetting<T> {
        /** The {@code -Dcaustica.*} system property name that overrides this setting. */
        String key();

        /** The dotted path of this setting inside the nested {@code config/caustica.toml} tables. */
        String tomlPath();

        T defaultValue();

        T get();

        void set(T value);

        void reloadFromSystemProperties();

        /** Writes this setting's current value into the given config at {@link #tomlPath()}. */
        void writeToFile(CommentedConfig config);

        /**
         * The settings-screen group this setting appears in, or null when it has no row. Most settings are
         * startup or diagnostic knobs with no business in a screen, so a row is opt-in via {@code inGroup}.
         */
        String group();

        /** Label key for the settings screen; {@code ".tooltip"} is appended for the description. */
        default String translationKey() {
            return "caustica.setting." + tomlPath();
        }
    }

    public static final class BooleanSetting implements RuntimeSetting<Boolean> {
        private final String key;
        private final String tomlPath;
        private final boolean defaultValue;
        private volatile boolean value;
        private String group;

        private BooleanSetting(String key, String tomlPath, boolean defaultValue) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = defaultValue;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Boolean defaultValue() {
            return defaultValue;
        }

        @Override
        public Boolean get() {
            return value;
        }

        public boolean value() {
            return value;
        }

        @Override
        public void set(Boolean value) {
            this.value = value != null ? value : defaultValue;
        }

        @Override
        public void reloadFromSystemProperties() {
            set(Boolean.parseBoolean(System.getProperty(key, Boolean.toString(defaultValue))));
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        @Override
        public String group() {
            return group;
        }

        /** Gives this setting a row in the named settings-screen group. */
        public BooleanSetting inGroup(String group) {
            this.group = group;
            return this;
        }

        private boolean resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                return Boolean.parseBoolean(prop.trim());
            }
            Boolean fromFile = fileBoolean(tomlPath);
            return fromFile != null ? fromFile : defaultValue;
        }
    }

    public static final class IntSetting implements RuntimeSetting<Integer> {
        private final String key;
        private final String tomlPath;
        private final int defaultValue;
        private final int minimum;
        private final int maximum;
        /** Non-empty for a setting whose legal values are an unevenly spaced set rather than a span. */
        private final List<Integer> choices;
        private volatile int value;
        private String group;
        private int sliderMinimum;
        private int sliderMaximum;

        private IntSetting(String key, String tomlPath, int defaultValue, int minimum, int maximum,
                           List<Integer> choices) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.minimum = minimum;
            this.maximum = maximum;
            this.sliderMinimum = minimum;
            this.sliderMaximum = maximum;
            this.choices = List.copyOf(choices);
            // A choice setting's declared default is authoritative — it is what an unrecognised value falls
            // back to — so only a span default is clamped.
            this.defaultValue = this.choices.isEmpty() ? Math.clamp(defaultValue, minimum, maximum) : defaultValue;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        public int minimum() {
            return minimum;
        }

        public int maximum() {
            return maximum;
        }

        /**
         * The span a slider covers, which may sit inside {@link #minimum()}/{@link #maximum()}: a setting
         * whose useful values occupy a small part of its legal range is unusable as a linear slider
         * otherwise, and narrowing the clamp instead would truncate a value someone set deliberately.
         */
        public int sliderMinimum() {
            return sliderMinimum;
        }

        public int sliderMaximum() {
            return sliderMaximum;
        }

        public IntSetting sliderRange(int sliderMinimum, int sliderMaximum) {
            this.sliderMinimum = sliderMinimum;
            this.sliderMaximum = sliderMaximum;
            return this;
        }

        public List<Integer> choices() {
            return choices;
        }

        private int sanitize(int candidate) {
            if (!choices.isEmpty()) {
                return choices.contains(candidate) ? candidate : defaultValue;
            }
            return Math.clamp(candidate, minimum, maximum);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Integer defaultValue() {
            return defaultValue;
        }

        @Override
        public Integer get() {
            return value;
        }

        public int value() {
            return value;
        }

        @Override
        public void set(Integer value) {
            this.value = sanitize(value != null ? value : defaultValue);
        }

        @Override
        public void reloadFromSystemProperties() {
            String prop = System.getProperty(key);
            if (prop == null) {
                this.value = defaultValue;
                return;
            }
            try {
                this.value = sanitize(Integer.parseInt(prop.trim()));
            } catch (NumberFormatException e) {
                this.value = defaultValue;
            }
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        @Override
        public String group() {
            return group;
        }

        /** Gives this setting a row in the named settings-screen group. */
        public IntSetting inGroup(String group) {
            this.group = group;
            return this;
        }

        private int resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                try {
                    return sanitize(Integer.parseInt(prop.trim()));
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
            Number fromFile = fileNumber(tomlPath);
            return fromFile != null ? sanitize(fromFile.intValue()) : defaultValue;
        }
    }

    public static final class FloatSetting implements RuntimeSetting<Float> {
        private final String key;
        private final String tomlPath;
        private final float defaultValue;
        private final float minimum;
        private final float maximum;
        private volatile float value;
        private String group;
        private float sliderMinimum;
        private float sliderMaximum;

        private FloatSetting(String key, String tomlPath, float rawDefault, float minimum, float maximum) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.minimum = minimum;
            this.maximum = maximum;
            this.sliderMinimum = minimum;
            this.sliderMaximum = maximum;
            this.defaultValue = (float) Math.clamp(rawDefault, minimum, maximum);
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        public float minimum() {
            return minimum;
        }

        public float maximum() {
            return maximum;
        }

        /**
         * The span a slider covers, which may sit inside {@link #minimum()}/{@link #maximum()}: a setting
         * whose useful values occupy a small part of its legal range is unusable as a linear slider
         * otherwise, and narrowing the clamp instead would truncate a value someone set deliberately.
         */
        public float sliderMinimum() {
            return sliderMinimum;
        }

        public float sliderMaximum() {
            return sliderMaximum;
        }

        public FloatSetting sliderRange(float sliderMinimum, float sliderMaximum) {
            this.sliderMinimum = sliderMinimum;
            this.sliderMaximum = sliderMaximum;
            return this;
        }

        /** A non-finite candidate falls back to the default rather than clamping to a bound. */
        private float sanitize(double candidate) {
            return Double.isFinite(candidate) ? (float) Math.clamp(candidate, minimum, maximum) : defaultValue;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Float defaultValue() {
            return defaultValue;
        }

        @Override
        public Float get() {
            return value;
        }

        public float value() {
            return value;
        }

        @Override
        public void set(Float value) {
            this.value = value != null ? sanitize(value) : defaultValue;
        }

        @Override
        public void reloadFromSystemProperties() {
            String prop = System.getProperty(key);
            if (prop == null) {
                this.value = defaultValue;
                return;
            }
            try {
                this.value = sanitize(Double.parseDouble(prop.trim()));
            } catch (NumberFormatException e) {
                this.value = defaultValue;
            }
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            // Round-trip through Float.toString() so the file gets the shortest decimal that reproduces this
            // float (e.g. "0.6"), not the widened double with float's binary noise spelled out to 17 digits
            // (e.g. 0.6000000487130328).
            config.set(tomlPath, Double.parseDouble(Float.toString(value)));
        }

        @Override
        public String group() {
            return group;
        }

        /** Gives this setting a row in the named settings-screen group. */
        public FloatSetting inGroup(String group) {
            this.group = group;
            return this;
        }

        private float resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                try {
                    return sanitize(Double.parseDouble(prop.trim()));
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
            Number fromFile = fileNumber(tomlPath);
            return fromFile != null ? sanitize(fromFile.doubleValue()) : defaultValue;
        }
    }

    public static final class StringSetting implements RuntimeSetting<String> {
        private final String key;
        private final String tomlPath;
        private final String defaultValue;
        private final List<String> choices;
        private volatile String value;
        private String group;

        private StringSetting(String key, String tomlPath, String defaultValue, List<String> choices) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = defaultValue;
            this.choices = List.copyOf(choices);
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        public List<String> choices() {
            return choices;
        }

        /** Matching is case-insensitive so a hand-edited file reads naturally; the stored form is canonical. */
        private String sanitize(String candidate) {
            if (candidate == null) {
                return defaultValue;
            }
            for (String choice : choices) {
                if (choice.equalsIgnoreCase(candidate)) {
                    return choice;
                }
            }
            return choices.isEmpty() ? candidate : defaultValue;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public String defaultValue() {
            return defaultValue;
        }

        @Override
        public String get() {
            return value;
        }

        @Override
        public void set(String value) {
            this.value = sanitize(value);
        }

        @Override
        public void reloadFromSystemProperties() {
            set(System.getProperty(key, defaultValue));
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        @Override
        public String group() {
            return group;
        }

        /** Gives this setting a row in the named settings-screen group. */
        public StringSetting inGroup(String group) {
            this.group = group;
            return this;
        }

        private String resolveInitial() {
            String prop = System.getProperty(key);
            return sanitize(prop != null ? prop : fileString(tomlPath));
        }
    }

    public static final class OptionalStringSetting implements RuntimeSetting<String> {
        private final String key;
        private final String tomlPath;
        private volatile String value;

        private OptionalStringSetting(String key, String tomlPath) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public String defaultValue() {
            return null;
        }

        @Override
        public String get() {
            return value;
        }

        @Override
        public void set(String value) {
            this.value = value;
        }

        @Override
        public void reloadFromSystemProperties() {
            this.value = System.getProperty(key);
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            if (value != null) {
                config.set(tomlPath, value);
            } else {
                config.remove(tomlPath);
            }
        }

        /** Never a settings-screen row: these hold filesystem paths and slot bindings, which get their own. */
        @Override
        public String group() {
            return null;
        }

        private String resolveInitial() {
            String prop = System.getProperty(key);
            return prop != null ? prop : fileString(tomlPath);
        }
    }

    public static final class Rt {
        public static final BooleanSetting ENABLED = bool("caustica.rt", "enabled", true).inGroup("general");
        public static final IntSetting WORKER_THREADS =
                intAtLeast("caustica.rt.workerThreads", "worker-threads", defaultWorkerThreads(), 1);

        private Rt() {
        }

        public static final class Composite {
            public static final IntSetting DEBUG_VIEW =
                    clampedInt("caustica.rt.debugView", "composite.debug-view", 0, 0, 15).inGroup("debug");
            public static final IntSetting MAX_BOUNCES =
                    clampedInt("caustica.rt.maxBounces", "composite.max-bounces", 4, 2, 8).inGroup("quality");
            public static final BooleanSetting WATER_WAVES =
                    bool("caustica.rt.waterWaves", "composite.water-waves", true).inGroup("look");
            // Sun/moon angular radii and the noon south tilt are not here: sky shape belongs to the
            // extension that owns the sky, as options SkyLutPass declares and CausticaOptions stores.
            public static final FloatSetting JITTER_SIGN_X =
                    finiteFloat("caustica.rt.jitterSignX", "composite.jitter-sign-x", 1.0f);
            public static final FloatSetting JITTER_SIGN_Y =
                    finiteFloat("caustica.rt.jitterSignY", "composite.jitter-sign-y", -1.0f);

            private Composite() {
            }
        }

        public static final class Terrain {
            // External keys retain their historical "per-tick" names for config compatibility; terrain
            // streaming is render-pass driven and these Java names reflect the actual scheduling unit.
            public static final IntSetting ASYNC_DISPATCH_PER_PASS =
                    intAtLeast("caustica.rt.asyncDispatchPerTick", "terrain.async-dispatch-per-tick", 32, 0);
            public static final IntSetting COMPLETION_RESULTS_PER_PASS =
                    intAtLeast("caustica.rt.sectionResultsPerTick", "terrain.section-results-per-tick", 32, 0);
            public static final IntSetting MAX_INFLIGHT_SECTIONS =
                    intAtLeast("caustica.rt.maxInflightSections", "terrain.max-inflight-sections", 32, 0);
            public static final IntSetting REBASE_DISTANCE_BLOCKS =
                    intAtLeast("caustica.rt.rebaseDistanceBlocks", "terrain.rebase-distance-blocks", 128, 0);

            private Terrain() {
            }
        }

        /** Minecraft emitter discovery parameters retained for the light-provider API. */
        public static final class Lights {
            public static final FloatSetting MIN_FILL_RATIO =
                    finiteFloat("caustica.rt.lightMinFillRatio", "lights.min-fill-ratio", 0.25f);

            private Lights() {
            }
        }

        public static final class Entities {
            public static final BooleanSetting ENABLED =
                    bool("caustica.rt.entities", "entities.enabled", true).inGroup("entities");
            public static final BooleanSetting PARTICLES_ENABLED =
                    bool("caustica.rt.particles", "particles.enabled", true).inGroup("entities");
            public static final BooleanSetting GLOW_ENABLED =
                    bool("caustica.rt.glow", "entities.glow.enabled", true).inGroup("entities");
            public static final BooleanSetting NAME_TAGS_ENABLED =
                    bool("caustica.rt.nameTags", "entities.name-tags.enabled", true);
            public static final IntSetting MAX_ORDINARY_ENTITIES =
                    intAtLeast("caustica.rt.maxOrdinaryEntities", "entities.max-ordinary-entities", 1024, 0);
            public static final IntSetting MAX_BLOCK_ENTITIES =
                    intAtLeast("caustica.rt.maxBlockEntities", "entities.block-entities.max-entities", 1024, 0);
            public static final IntSetting MAX_PARTICLES =
                    intAtLeast("caustica.rt.maxParticles", "particles.max-particles", 1024, 0);
            public static final IntSetting BE_VIEW_CHUNKS =
                    intAtLeast("caustica.rt.beViewChunks", "entities.block-entities.view-chunks", 8, 0);
            public static final IntSetting BE_BUILDS_PER_FRAME =
                    intAtLeast("caustica.rt.beBuildsPerFrame", "entities.block-entities.builds-per-frame", 128, 0);
            private Entities() {
            }

            public static int maxEntities() {
                return Math.addExact(Math.addExact(
                        MAX_ORDINARY_ENTITIES.value(), MAX_BLOCK_ENTITIES.value()), MAX_PARTICLES.value());
            }

            public static int entityListCapacity() {
                return Math.max(16, maxEntities());
            }

            public static int entityMapCapacity() {
                // Fastutil expected-size constructors apply their own load-factor headroom.
                return Math.max(16, MAX_ORDINARY_ENTITIES.value());
            }
        }

        public static final class Overlay {
            public static final BooleanSetting BLOCK_OUTLINE_ENABLED =
                    bool("caustica.rt.blockOutline", "overlay.block-outline.enabled", true).inGroup("entities");

            private Overlay() {
            }
        }

        public static final class DlssRr {
            public static final IntSetting PRESET = intValue("caustica.rt.dlssRr.preset", "dlss-rr.preset", 0);

            // NVSDK_NGX_PerfQuality_Value. Per NVIDIA's DLSS-RR programming guide, Ray Reconstruction only
            // supports Performance(0), Balanced(1), Quality(2), Ultra-Performance(3), and DLAA(5) —
            // Ultra Quality(4) is not a valid PerfQualityValue for RR (its optimal-settings query returns a
            // zeroed render size for it) and is deliberately excluded here.
            public static final List<Integer> QUALITY_STEPS = List.of(3, 0, 1, 2, 5);
            public static final IntSetting QUALITY =
                    intChoice("caustica.rt.dlssRr.quality", "dlss-rr.quality", 0, QUALITY_STEPS).inGroup("upscaling");

            private DlssRr() {
            }
        }

        public static final class DlssSr {
            public static final IntSetting PRESET = intChoice(
                    "caustica.rt.dlssSr.preset", "dlss-sr.preset", 0, List.of(0, 10, 11, 12, 13));
            public static final List<Integer> QUALITY_STEPS = List.of(3, 0, 1, 2, 5);
            public static final IntSetting QUALITY = intChoice(
                    "caustica.rt.dlssSr.quality", "dlss-sr.quality", 2, QUALITY_STEPS).inGroup("upscaling");

            private DlssSr() {
            }
        }

        /** Selects the single consumer of the path-traced image and its temporal guides. */
        public static final class Denoising {
            public static final StringSetting ROUTE = stringChoice(
                    "caustica.rt.denoisingRoute", "denoising.route", "ray_reconstruction",
                    List.of("ray_reconstruction", "temporal_denoiser", "raw")).inGroup("upscaling");
            public static final StringSetting METHOD = stringChoice(
                    "caustica.rt.denoisingMethod", "denoising.method", "reblur",
                    List.of("relax", "reblur")).inGroup("upscaling");

            private Denoising() {
            }
        }

        /** DLSS Frame Generation. Default off; gated additionally by hardware/driver availability. */
        public static final class Fg {
            public static final BooleanSetting ENABLED =
                    bool("caustica.rt.fg", "frame-generation.enabled", false).inGroup("upscaling");

            private Fg() {
            }
        }

        /**
         * NVIDIA Reflex ({@code VK_NV_low_latency2}). Default off; gated additionally by device support.
         * The renderer configures the swapchain latency mode, paces frames with {@code vkLatencySleepNV},
         * and emits simulation, render-submit, and present latency markers.
         */
        public static final class Reflex {
            public static final BooleanSetting ENABLED =
                    bool("caustica.rt.reflex", "reflex.enabled", false).inGroup("upscaling");
            public static final BooleanSetting LOW_LATENCY_BOOST =
                    bool("caustica.rt.reflex.boost", "reflex.low-latency-boost", false);
            public static final IntSetting MINIMUM_INTERVAL_US =
                    intAtLeast("caustica.rt.reflex.minIntervalUs", "reflex.minimum-interval-us", 0, 0);

            private Reflex() {
            }
        }

        public static final class Exposure {
            // Control points are measured-EV100 : compensation-EV.
            // Rendered median (log) = log2(key) + comp(evScene), so comp IS the rendered offset in EV
            // from the noon reference.
            //
            // Fitted to measured in-game EV100 and the current emissive baseline:
            //   noon sand       +17.45 -> -0.01   renders at key, the reference
            //   noon blue sky   +16.50 -> -0.17
            //   daylight shade   +7.00 -> -1.82
            //   lit night room   +7.00 -> -1.82   (same measured luminance as daylight shade)
            //   night street     +1.50 -> -3.01
            //   starlit sky      -8.00 -> -5.00   (floor)
            // Effective slope is 0.79 / 0.78 / 0.83 across the three segments, compressing 25 EV of
            // scene range to 5.0 EV of rendered difference.
            //
            // Daylight shade and a lit interior at night measure the SAME (~EV 7), so no luminance-only
            // curve can separate them -- what does is the asymmetric temporal adaptation above, which
            // holds a low exposure when you step from noon sun into shade. That is a real limit of this
            // controller, not a tuning miss.
            public static final List<String> MODES = List.of("auto", "manual");
            public static final StringSetting MODE =
                    stringChoice("caustica.rt.exposure.mode", "exposure.mode", "auto", MODES).inGroup("exposure");
            public static final FloatSetting MANUAL_EV =
                    clampedFloat("caustica.rt.exposure.manualEv", "exposure.manual-ev",
                            0.0f, -15.0f, 15.0f).inGroup("exposure");
            public static final FloatSetting KEY = exposureScale("caustica.rt.exposure.key", "exposure.key", 0.18f);
            // Bounds on the ABSOLUTE exposure multiplier. Sized from what the curve above actually asks
            // for at the measured scene extremes: -16.9 EV at noon sand, +3.5 EV at the starlit-sky
            // floor. A clamp should be a guard rail, not the controller, so these sit just outside that.
            //
            // max-ev was +10 and blew out the frame: with 13 EV of headroom above what the curve wants,
            // exposure ran away whenever the camera held something very dark, and anything bright
            // entering the frame then arrived pre-blown. +5 keeps 1.5 EV over the curve's own demand.
            //
            // min-ev deliberately does NOT cover a zoomed-in sun (which asks for about -20.8): letting
            // the whole frame go black because the sun is in shot is worse than clamping it. The sky
            // metering cap already bounds the sun's share, so in practice this only engages on a
            // near-full-screen sun.
            /**
             * Adaptation time constants in seconds, applied in EV space by the resolve. Named for what
             * the SCENE did: walking into a dark cave is "darken" (exposure has to rise), stepping back
             * out is "brighten".
             *
             * <p>Asymmetric on purpose, and in the direction human vision actually works — light
             * adaptation takes seconds, dark adaptation takes minutes. Every shipping game compresses
             * that, but keeping the sign right is what makes a sunrise read as a sunrise instead of as a
             * lens. The names describe the scene change, not the inverse movement of the exposure multiplier.
             */
            public static final FloatSetting ADAPT_DARKEN =
                    exposureScale("caustica.rt.exposure.adaptDarken", "exposure.adapt-darken", 2.0f);
            public static final FloatSetting ADAPT_BRIGHTEN =
                    exposureScale("caustica.rt.exposure.adaptBrighten", "exposure.adapt-brighten", 0.4f);
            public static final FloatSetting LOW_PERCENTILE =
                    clampedFloat("caustica.rt.exposure.lowPercentile", "exposure.low-percentile", 0.50f, 0.0f, 1.0f);
            public static final FloatSetting HIGH_PERCENTILE =
                    clampedFloat("caustica.rt.exposure.highPercentile", "exposure.high-percentile", 0.95f, 0.0f, 1.0f);
            public static final IntSetting STRIDE =
                    clampedInt("caustica.rt.exposure.stride", "exposure.stride", 2, 1, 8);
            public static final FloatSetting CENTER_WEIGHT_SIGMA =
                    clampedFloat("caustica.rt.exposure.centerWeightSigma",
                            "exposure.center-weight-sigma", 0.35f, 0.01f, 2.0f);
            public static final FloatSetting CENTER_WEIGHT_FLOOR =
                    clampedFloat("caustica.rt.exposure.centerWeightFloor",
                            "exposure.center-weight-floor", 0.15f, 0.0f, 1.0f);
            public static final FloatSetting SKY_WEIGHT_CAP =
                    clampedFloat("caustica.rt.exposure.skyWeightCap",
                            "exposure.sky-weight-cap", 0.25f, 0.0f, 1.0f);
            public static final FloatSetting EMISSIVE_WEIGHT_CAP =
                    clampedFloat("caustica.rt.exposure.emissiveWeightCap",
                            "exposure.emissive-weight-cap", 0.10f, 0.0f, 1.0f);
            /**
             * Pre-exposure: raygen multiplies scene radiance by the previous frame's exposure before
             * the fp16 write, and the display pass divides it back out, so stored values sit near
             * {@code key} instead of spanning the ~26 EV physical photometric units require. The two
             * cancel algebraically, so <b>toggling this must not change the
             * image</b>; it exists as an A/B switch for exactly that check, and as an escape hatch
             * if DLSS-RR ever proves sensitive to its history being at the previous frame's scale.
             */
            public static final BooleanSetting PRE_EXPOSURE =
                    bool("caustica.rt.exposure.preExposure", "exposure.pre-exposure", true);

            private Exposure() {
            }

            /**
             * Sanity bound on an exposure multiplier, not an artistic one. It must remain below the
             * 3.8e-6 multiplier requested by {@code -18 EV}; min-ev/max-ev provides the artistic bound.
             */
            public static float clampScale(float value) {
                return Math.clamp(value, 1.0e-8f, 1.0e8f);
            }


        }

        /** Scene-referred look transform and baked SDR/HDR ACES display transforms. */
        public static final class Tonemap {
            public static final FloatSetting GAMMA =
                    clampedFloat("caustica.rt.tonemap.gamma", "tonemap.gamma", 1.0f, 0.1f, 5.0f)
                            .inGroup("look").sliderRange(0.5f, 1.5f);

            private Tonemap() {
            }
        }

        /** Render-frame timing + hitch logging. See {@code RtFrameStats}. */
        public static final class FrameStats {
            public static final BooleanSetting ENABLED =
                    bool("caustica.rt.frameStats", "frame-stats.enabled", false).inGroup("debug");

            private FrameStats() {
            }
        }

        /** Optional high-dynamic-range screenshot output paired with vanilla's F2 PNG. */
        public static final class Screenshots {
            public static final BooleanSetting EXR_ENABLED =
                    bool("caustica.rt.screenshots.exr", "screenshots.exr-enabled", false).inGroup("debug");

            private Screenshots() {
            }
        }

        /**
         * HDR display output. When enabled the swapchain is created in PQ (ST.2084/HDR10 — the display-ready
         * encoding both HDR10 swapchains and DLSS Frame Generation require; whatever pixel format the surface
         * pairs with that color space, commonly a 10-bit UNORM), falling back to SDR if the surface doesn't
         * advertise it. The ACES LUT owns scene-to-display mapping; {@code uiNits} places SDR-authored UI
         * in that PQ output, while {@code peakNits} selects the LUT's mastering target.
         */
        public static final class Hdr {
            public static final BooleanSetting ENABLED =
                    bool("caustica.rt.hdr", "hdr.enabled", false).inGroup("output");
            public static final FloatSetting UI_NITS =
                    clampedFloat("caustica.rt.hdr.uiNits", "hdr.ui-nits", 200.0f, 80.0f, 500.0f).inGroup("output");

            // ACES HDR LUTs are available only for these mastering targets.
            public static final List<Integer> PEAK_NITS_STEPS = List.of(500, 1000, 2000, 4000);
            public static final IntSetting PEAK_NITS =
                    intChoice("caustica.rt.hdr.peakNits", "hdr.peak-nits", 1000, PEAK_NITS_STEPS).inGroup("output");

            // Surface capability and current swapchain state are separate: HDR controls remain available
            // while the swapchain is native SDR, so enabling HDR can recreate it in PQ.
            private static volatile boolean SWAPCHAIN_PQ_AVAILABLE = false;
            private static volatile boolean SWAPCHAIN_PQ_ACTIVE = false;

            private Hdr() {
            }

            public static void setSwapchainPqAvailable(boolean available) {
                SWAPCHAIN_PQ_AVAILABLE = available;
            }

            public static void setSwapchainPqActive(boolean active) {
                SWAPCHAIN_PQ_ACTIVE = active;
            }

            /**
             * Whether this session's surface can create a PQ swapchain, independent of which format the
             * current swapchain uses.
             */
            public static boolean swapchainPqAvailable() {
                return SWAPCHAIN_PQ_AVAILABLE;
            }

            /** Whether the currently configured swapchain is HDR10/PQ rather than native SDR. */
            public static boolean swapchainPqActive() {
                return SWAPCHAIN_PQ_ACTIVE;
            }

            /**
             * Whether the HDR display path (world HDR + PQ swapchain + UI overlay) should be active this
             * frame. The option invalidates the surface configuration after changing {@link #ENABLED};
             * the ordinary resize/configure path recreates the swapchain in SDR or PQ.
             */
            public static boolean enabled() {
                return SWAPCHAIN_PQ_ACTIVE && ENABLED.value();
            }

            /** Absolute brightness assigned to SDR-authored UI in the PQ output. */
            public static float uiNits() {
                return UI_NITS.value();
            }

        }

        /**
         * Which feature is bound to each engine slot, as a {@code namespace:path} feature id. Null means the
         * registry's own default binding, so an untouched install has no keys here at all.
         *
         * <p>Lives in this file rather than {@code caustica-options.toml} because a slot binding is engine
         * state, not a value an extension declared: that file's key space is derived entirely from
         * registered {@code Option}s and has to stay that way.
         */
        public static final class Composition {
            public static final OptionalStringSetting SKY =
                    optionalString("caustica.composition.sky", "composition.slots.sky");

            private Composition() {
            }
        }
    }

    public static final class Ngx {
        public static final OptionalStringSetting PATH = optionalString("caustica.ngx.path", "ngx.path");

        private Ngx() {
        }
    }

    public static final class Slang {
        public static final OptionalStringSetting PATH = optionalString("caustica.slang.path", "slang.path");

        private Slang() {
        }
    }

    private static BooleanSetting bool(String key, String tomlPath, boolean fallback) {
        return new BooleanSetting(key, tomlPath, fallback);
    }

    private static StringSetting stringChoice(String key, String tomlPath, String fallback, List<String> choices) {
        return new StringSetting(key, tomlPath, fallback, choices);
    }

    private static OptionalStringSetting optionalString(String key, String tomlPath) {
        return new OptionalStringSetting(key, tomlPath);
    }

    private static IntSetting intValue(String key, String tomlPath, int fallback) {
        return new IntSetting(key, tomlPath, fallback, Integer.MIN_VALUE, Integer.MAX_VALUE, List.of());
    }

    private static IntSetting intAtLeast(String key, String tomlPath, int fallback, int min) {
        return new IntSetting(key, tomlPath, fallback, min, Integer.MAX_VALUE, List.of());
    }

    private static IntSetting intChoice(String key, String tomlPath, int fallback, List<Integer> choices) {
        return new IntSetting(key, tomlPath, fallback, Integer.MIN_VALUE, Integer.MAX_VALUE, choices);
    }

    private static IntSetting clampedInt(String key, String tomlPath, int fallback, int min, int max) {
        return new IntSetting(key, tomlPath, fallback, min, max, List.of());
    }

    private static FloatSetting finiteFloat(String key, String tomlPath, float fallback) {
        return new FloatSetting(key, tomlPath, fallback, -Float.MAX_VALUE, Float.MAX_VALUE);
    }

    private static FloatSetting exposureScale(String key, String tomlPath, float fallback) {
        return new FloatSetting(key, tomlPath, fallback, 1.0e-4f, 1.0e4f);
    }

    private static FloatSetting clampedFloat(String key, String tomlPath, float fallback, float min, float max) {
        return new FloatSetting(key, tomlPath, fallback, min, max);
    }

    private static int defaultWorkerThreads() {
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
    }
}
