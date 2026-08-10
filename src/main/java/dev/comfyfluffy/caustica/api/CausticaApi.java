package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.CausticaOptions;

import java.util.Objects;

/** Process-wide access to the registry and option store installed by the current host adapter. */
public final class CausticaApi {
    public static final String VERSION = "0.1.0";
    public static final String ENTRYPOINT = "caustica";
    private static CausticaRegistry registry;
    private static CausticaOptions options;

    private CausticaApi() {
    }

    public static synchronized void initialize(CausticaRegistry installedRegistry,
                                               CausticaOptions installedOptions) {
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

    /** Every registered feature's {@code Option} values. */
    public static synchronized CausticaOptions options() {
        if (options == null) {
            throw new IllegalStateException("Caustica API has not been initialized by a host adapter");
        }
        return options;
    }
}
