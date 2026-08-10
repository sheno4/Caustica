package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.CausticaOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

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
        // After registration, so a saved id can be resolved against the features that actually loaded, and
        // before selection(), so the first composition the renderer compiles is already the chosen one.
        applyPersistedSelection(REGISTRY, Slots.SKY, CausticaConfig.Rt.Composition.SKY.get());
        REGISTRY.selection();
        // Loaded here, not from the renderer: option values have to be readable before any GPU context
        // exists (a settings screen opened from the title menu) and before the engine decides which passes
        // and providers to instantiate at all.
        options = CausticaOptions.load(REGISTRY.features());
        initialized = true;
        CausticaMod.LOGGER.info("Caustica extension API {} initialized with {} feature(s)",
                VERSION, REGISTRY.features().size());
    }

    /**
     * Binds a slot to the feature saved in {@code caustica.toml}, if that feature is installed and binds it.
     * A saved id that no longer resolves leaves the slot on its default for this session but is deliberately
     * left in the file: reinstalling the extension restores the binding, and the screen reads the slot as
     * being on its default meanwhile, so nothing claims otherwise.
     */
    static void applyPersistedSelection(CausticaRegistry registry, Slot slot, String saved) {
        if (saved == null) {
            return;
        }
        Identifier featureId = Identifier.tryParse(saved);
        Feature feature = featureId != null ? registry.features().get(featureId) : null;
        if (feature == null || !feature.bindings().containsKey(slot)) {
            CausticaMod.LOGGER.warn("Slot {} is saved as '{}', which is not installed or does not bind it; "
                    + "using the default", slot.id(), saved);
            return;
        }
        registry.select(slot, featureId);
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
