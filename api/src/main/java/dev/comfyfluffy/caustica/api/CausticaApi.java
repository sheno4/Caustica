package dev.comfyfluffy.caustica.api;

import java.util.Objects;

/** Process-wide access to the registry and option store installed by the current host adapter. */
public final class CausticaApi {
    public static final String VERSION = "0.3.0";
    public static final String ENTRYPOINT = "caustica";
    private static CausticaRegistry registry;
    private static OptionLookup options;

    private CausticaApi() {
    }

    public static synchronized void initialize(CausticaRegistry installedRegistry,
                                               OptionLookup installedOptions) {
        Objects.requireNonNull(installedRegistry, "installedRegistry");
        Objects.requireNonNull(installedOptions, "installedOptions");
        if (registry != null) {
            throw new IllegalStateException("Caustica API is already initialized");
        }
        installedRegistry.selection();
        registry = installedRegistry;
        options = installedOptions;
    }

    public static synchronized CausticaRegistry registry() {
        if (registry == null) {
            throw new IllegalStateException("Caustica API has not been initialized by a host adapter");
        }
        return registry;
    }

    /** The current values for one registered feature's declared options. */
    public static synchronized OptionValues options(ResourceId featureId) {
        if (options == null) {
            throw new IllegalStateException("Caustica API has not been initialized by a host adapter");
        }
        return options.options(Objects.requireNonNull(featureId, "featureId"));
    }

    /** Host-neutral option lookup used by render-session infrastructure. */
    public static synchronized OptionLookup optionLookup() {
        if (options == null) {
            throw new IllegalStateException("Caustica API has not been initialized by a host adapter");
        }
        return options;
    }
}
