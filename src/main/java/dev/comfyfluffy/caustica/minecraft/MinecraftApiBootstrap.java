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
import dev.comfyfluffy.caustica.builtin.BuiltinExtension;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftApiExtension;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtensionRegistry;
import dev.comfyfluffy.caustica.platform.CausticaPlatform;
import dev.comfyfluffy.caustica.rt.RtRuntime;
import dev.comfyfluffy.caustica.slang.SlangPassShaderCompiler;

/** Loader-neutral extension discovery, config paths, and Minecraft provider installation. */
public final class MinecraftApiBootstrap {
    private static MinecraftExtensionRegistry minecraftExtensions;

    private MinecraftApiBootstrap() {
    }

    public static void initialize() {
        MinecraftTelemetry.install(RtRuntime.INSTANCE.telemetry());
        CausticaRegistry registry = new CausticaRegistry();
        new BuiltinExtension().register(registry);
        new MinecraftProvidersExtension().register(registry);
        MinecraftExtensionRegistry minecraftRegistry = new MinecraftExtensionRegistry();
        registerExtensions(registry, minecraftRegistry, CausticaPlatform.current().extensions());
        minecraftExtensions = minecraftRegistry;
        applyPersistedSelection(registry, Slots.SKY, CausticaConfig.Rt.Composition.SKY.get(),
                MinecraftProvidersExtension.ID);
        var shaderCache = CausticaPlatform.current().gameDir().resolve("caustica-shaders");
        SlangPassShaderCompiler.install(shaderCache.resolve("passes"));
        RtRuntime.INSTANCE.configureShaderCache(shaderCache.resolve("sources"));
        RtRuntime.INSTANCE.telemetry().configure(CausticaPlatform.current().gameDir()
                .resolve("rt-frame-stats"), MinecraftFrameMetrics.schema());
        CausticaOptions options = CausticaOptions.load(
                CausticaPlatform.current().configDir().resolve("caustica-options.toml"),
                registry.features());
        CausticaApi.initialize(registry, options);
        CausticaMod.LOGGER.info("Caustica extension API {} initialized with {} feature(s)",
                CausticaApi.VERSION, registry.features().size());
    }

    static void registerExtensions(CausticaRegistry registry, MinecraftExtensionRegistry minecraftRegistry,
                                   java.util.List<CausticaExtension> extensions) {
        for (CausticaExtension extension : extensions) {
            try {
                extension.register(registry);
            } catch (RuntimeException e) {
                CausticaMod.LOGGER.error("Caustica feature registration failed: {}",
                        extension.getClass().getName(), e);
            }
            if (extension instanceof MinecraftApiExtension minecraftExtension) {
                try {
                    minecraftExtension.registerMinecraft(minecraftRegistry);
                } catch (RuntimeException e) {
                    CausticaMod.LOGGER.error("Minecraft host registration failed: {}",
                            extension.getClass().getName(), e);
                }
            }
        }
        minecraftRegistry.freeze();
    }

    public static MinecraftExtensionRegistry minecraftExtensions() {
        MinecraftExtensionRegistry current = minecraftExtensions;
        if (current == null) throw new IllegalStateException("Minecraft extension API is not initialized");
        return current;
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
