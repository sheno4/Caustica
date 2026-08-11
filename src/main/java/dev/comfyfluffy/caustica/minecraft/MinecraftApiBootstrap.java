package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.CausticaOptions;
import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.Feature;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.Slot;
import dev.comfyfluffy.caustica.api.Slots;
import dev.comfyfluffy.caustica.api.pass.PassShaderCompiler;
import dev.comfyfluffy.caustica.builtin.BuiltinExtension;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.RtComposite;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric discovery, config paths, and Minecraft provider installation for the public renderer API. */
public final class MinecraftApiBootstrap {
    private MinecraftApiBootstrap() {
    }

    public static void initialize() {
        CausticaRegistry registry = new CausticaRegistry();
        new BuiltinExtension().register(registry);
        new MinecraftProvidersExtension().register(registry);
        for (CausticaExtension extension : FabricLoader.getInstance()
                .getEntrypoints(CausticaApi.ENTRYPOINT, CausticaExtension.class)) {
            try {
                extension.register(registry);
            } catch (RuntimeException e) {
                CausticaMod.LOGGER.error("Caustica extension registration failed: {}",
                        extension.getClass().getName(), e);
            }
        }
        applyPersistedSelection(registry, Slots.SKY, CausticaConfig.Rt.Composition.SKY.get(),
                MinecraftProvidersExtension.ID);
        var shaderCache = FabricLoader.getInstance().getGameDir().resolve("caustica-shaders");
        PassShaderCompiler.defaultCacheRoot(shaderCache.resolve("passes"));
        RtComposite.configureShaderCacheRoot(shaderCache.resolve("sources"));
        RtFrameStats.configureOutputDirectory(FabricLoader.getInstance().getGameDir()
                .resolve("rt-frame-stats"));
        RtFrameStats.configureFrameMetrics(MinecraftFrameMetrics.schema());
        CausticaOptions options = CausticaOptions.load(
                FabricLoader.getInstance().getConfigDir().resolve("caustica-options.toml"),
                registry.features());
        CausticaApi.initialize(registry, options);
        CausticaMod.LOGGER.info("Caustica extension API {} initialized with {} feature(s)",
                CausticaApi.VERSION, registry.features().size());
    }

    static void applyPersistedSelection(CausticaRegistry registry, Slot slot, String saved,
                                        ResourceId hostDefault) {
        if (saved == null) {
            registry.select(slot, hostDefault);
            return;
        }
        ResourceId featureId = ResourceId.tryParse(saved);
        Feature feature = featureId != null ? registry.features().get(featureId) : null;
        if (feature == null || !feature.bindings().containsKey(slot)) {
            CausticaMod.LOGGER.warn("Slot {} is saved as '{}', which is not installed or does not bind it; "
                    + "using the default", slot.id(), saved);
            return;
        }
        registry.select(slot, featureId);
    }
}
