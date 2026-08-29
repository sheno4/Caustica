package dev.comfyfluffy.caustica.settings;


import java.util.Objects;

/**
 * Process-wide access to the settings registry and value store installed by the host adapter.
 */
public final class CausticaSettings {
    private static CausticaSettings instance;

    private final SettingsRegistry registry;
    private final OptionLookup options;

    private CausticaSettings(SettingsRegistry registry, OptionLookup options) {
        this.registry = registry;
        this.options = options;
    }

    public static synchronized void initialize(SettingsRegistry installedRegistry,
                                               OptionLookup installedOptions) {
        Objects.requireNonNull(installedRegistry, "installedRegistry");
        Objects.requireNonNull(installedOptions, "installedOptions");
        if (instance != null) {
            throw new IllegalStateException("Caustica settings are already initialized");
        }
        instance = new CausticaSettings(installedRegistry, installedOptions);
    }

    /** Returns the settings installed by the host adapter. */
    public static synchronized CausticaSettings getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Caustica settings have not been initialized by a host adapter");
        }
        return instance;
    }

    public SettingsRegistry registry() {
        return registry;
    }

    /**
     * Returns a live view of one feature's declared option values. Use {@link OptionLookup#snapshot()} when
     * values must remain consistent across a frame.
     */
    public OptionValues options(ResourceId featureId) {
        return options.options(Objects.requireNonNull(featureId, "featureId"));
    }

    /** Returns the installed lookup, including its snapshot operation. */
    public OptionLookup lookup() {
        return options;
    }
}
