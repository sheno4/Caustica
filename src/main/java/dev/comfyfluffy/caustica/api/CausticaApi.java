package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.CausticaOptions;
import net.fabricmc.loader.api.FabricLoader;

public final class CausticaApi {
    public static final String VERSION = "0.1.0";
    public static final String ENTRYPOINT = "caustica";
    private static final CausticaRegistry REGISTRY = CausticaRegistry.withBuiltins();
    private static CausticaOptions options;
    private static boolean initialized;

    private CausticaApi() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        for (CausticaExtension extension : FabricLoader.getInstance()
                .getEntrypoints(ENTRYPOINT, CausticaExtension.class)) {
            try {
                extension.register(REGISTRY);
            } catch (RuntimeException e) {
                CausticaMod.LOGGER.error("Caustica extension registration failed: {}",
                        extension.getClass().getName(), e);
            }
        }
        REGISTRY.selection();
        // Loaded here, not from the renderer: option values have to be readable before any GPU context
        // exists (a settings screen opened from the title menu) and, once feature enable/disable lands,
        // before the engine decides which passes and providers to instantiate at all.
        options = CausticaOptions.load(REGISTRY.features());
        initialized = true;
        CausticaMod.LOGGER.info("Caustica extension API {} initialized with {} feature(s)",
                VERSION, REGISTRY.features().size());
    }

    public static CausticaRegistry registry() {
        initialize();
        return REGISTRY;
    }

    /** Every registered feature's {@code Option} values. Process-scoped; see {@link CausticaOptions}. */
    public static CausticaOptions options() {
        initialize();
        return options;
    }
}
