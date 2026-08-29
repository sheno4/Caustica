package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.CausticaOptions;
import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.builtin.BuiltinExtension;
import dev.comfyfluffy.caustica.engine.session.RenderSessionHost;
import dev.comfyfluffy.caustica.engine.session.MinecraftWorldSessionHost;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;
import dev.comfyfluffy.caustica.platform.CausticaPlatform;
import dev.comfyfluffy.caustica.rt.RtRuntime;
import dev.comfyfluffy.caustica.settings.CausticaSettings;
import dev.comfyfluffy.caustica.settings.CausticaSettingsExtension;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;

import java.util.ArrayList;
import java.util.List;

/** Loader-neutral process extension discovery and settings installation. */
public final class MinecraftApiBootstrap {
    private static RenderSessionHost sessionHost;
    private static MinecraftWorldSessionHost minecraftSessionHost;
    private static SettingsRegistry settings;

    private MinecraftApiBootstrap() { }

    public static void initialize() {
        MinecraftTelemetry.install(RtRuntime.INSTANCE.telemetry());
        RenderSessionHost host = new RenderSessionHost();
        MinecraftWorldSessionHost minecraftHost = new MinecraftWorldSessionHost();
        SettingsRegistry settingsRegistry = new SettingsRegistry();
        List<CausticaExtension> extensions = new ArrayList<>();
        extensions.add(new BuiltinExtension());
        extensions.addAll(CausticaPlatform.current().extensions());
        registerExtensions(host, minecraftHost, settingsRegistry, extensions);
        registerMinecraftExtensions(minecraftHost, settingsRegistry,
                CausticaPlatform.current().minecraftExtensions(), extensions);
        registerMinecraftExtension(minecraftHost, settingsRegistry, new MinecraftProvidersExtension());

        var shaderCache = CausticaPlatform.current().gameDir().resolve("caustica-shaders");
        RtRuntime.INSTANCE.configureShaderCache(shaderCache.resolve("sources"));
        RtRuntime.INSTANCE.telemetry().configure(CausticaPlatform.current().gameDir()
                .resolve("rt-frame-stats"), MinecraftFrameMetrics.schema());
        CausticaOptions options = CausticaOptions.load(
                CausticaPlatform.current().configDir().resolve("caustica-options.toml"),
                settingsRegistry);
        CausticaSettings.initialize(settingsRegistry, options);

        sessionHost = host;
        minecraftSessionHost = minecraftHost;
        settings = settingsRegistry;
        RtRuntime.INSTANCE.installApiHost(host);
        CausticaMod.LOGGER.info("Caustica extension API {} initialized with {} settings feature(s)",
                dev.comfyfluffy.caustica.api.CausticaApi.VERSION, settingsRegistry.all().size());
    }

    static void registerMinecraftExtensions(MinecraftWorldSessionHost host, SettingsRegistry settingsRegistry,
                                            List<MinecraftExtension> extensions,
                                            List<CausticaExtension> genericExtensions) {
        for (MinecraftExtension extension : extensions) {
            if (genericExtensions.stream().anyMatch(generic -> generic == extension)) continue;
            registerMinecraftSessionExtension(host, extension);
            if (extension instanceof CausticaSettingsExtension settingsExtension) {
                try {
                    settingsExtension.registerSettings(settingsRegistry);
                } catch (RuntimeException failure) {
                    CausticaMod.LOGGER.error("Caustica settings registration failed: {}",
                            extension.getClass().getName(), failure);
                }
            }
        }
    }

    static void registerExtensions(RenderSessionHost host, MinecraftWorldSessionHost minecraftHost,
                                   SettingsRegistry settingsRegistry,
                                   List<CausticaExtension> extensions) {
        for (CausticaExtension extension : extensions) {
            try {
                extension.register(host.api());
            } catch (RuntimeException failure) {
                CausticaMod.LOGGER.error("Caustica session registration failed: {}",
                        extension.getClass().getName(), failure);
            }
            if (extension instanceof MinecraftExtension minecraftExtension) {
                registerMinecraftSessionExtension(minecraftHost, minecraftExtension);
            }
            if (extension instanceof CausticaSettingsExtension settingsExtension) {
                try {
                    settingsExtension.registerSettings(settingsRegistry);
                } catch (RuntimeException failure) {
                    CausticaMod.LOGGER.error("Caustica settings registration failed: {}",
                            extension.getClass().getName(), failure);
                }
            }
        }
    }

    private static void registerMinecraftExtension(MinecraftWorldSessionHost host,
                                                   SettingsRegistry settingsRegistry,
                                                   MinecraftProvidersExtension extension) {
        registerMinecraftSessionExtension(host, extension);
        try {
            extension.registerSettings(settingsRegistry);
        } catch (RuntimeException failure) {
            CausticaMod.LOGGER.error("Caustica settings registration failed: {}",
                    extension.getClass().getName(), failure);
        }
    }

    private static void registerMinecraftSessionExtension(MinecraftWorldSessionHost host,
                                                          MinecraftExtension extension) {
        try {
            extension.registerMinecraft(host.api());
        } catch (RuntimeException failure) {
            CausticaMod.LOGGER.error("Minecraft world-session registration failed: {}",
                    extension.getClass().getName(), failure);
        }
    }

    public static RenderSessionHost sessionHost() {
        RenderSessionHost current = sessionHost;
        if (current == null) throw new IllegalStateException("Caustica extension API is not initialized");
        return current;
    }

    public static SettingsRegistry settings() {
        SettingsRegistry current = settings;
        if (current == null) throw new IllegalStateException("Caustica settings are not initialized");
        return current;
    }

    public static MinecraftWorldSessionHost minecraftSessionHost() {
        MinecraftWorldSessionHost current = minecraftSessionHost;
        if (current == null) throw new IllegalStateException("Minecraft session API is not initialized");
        return current;
    }
}
