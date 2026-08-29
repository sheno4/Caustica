package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.config.CausticaOptions;
import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.builtin.BuiltinExtension;
import dev.comfyfluffy.caustica.engine.session.RenderSessionHost;
import dev.comfyfluffy.caustica.minecraft.adapter.session.MinecraftWorldSessionHost;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftClientMaterialEpochCompiler;
import dev.comfyfluffy.caustica.platform.CausticaPlatform;
import dev.comfyfluffy.caustica.rt.RtTelemetry;
import dev.comfyfluffy.caustica.nvidia.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.settings.CausticaSettings;
import dev.comfyfluffy.caustica.settings.CausticaSettingsExtension;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.slang.SlangRuntimeConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Loader-neutral process extension discovery and settings installation. */
public final class MinecraftApiBootstrap {
    private MinecraftApiBootstrap() { }

    public static ApiServices initialize(CausticaPlatform platform, RtTelemetry telemetry) {
        MinecraftTelemetry.install(telemetry);
        RenderSessionHost host = new RenderSessionHost();
        MinecraftWorldSessionHost minecraftHost = new MinecraftWorldSessionHost();
        SettingsRegistry settingsRegistry = new SettingsRegistry();
        List<CausticaExtension> extensions = new ArrayList<>();
        extensions.add(new BuiltinExtension());
        extensions.addAll(platform.extensions());
        registerExtensions(host, minecraftHost, settingsRegistry, extensions);
        registerMinecraftExtensions(minecraftHost, settingsRegistry,
                platform.minecraftExtensions(), extensions);
        MinecraftLightingCalibration calibration = MinecraftLightingCalibrationLoader.loadDefault();
        registerMinecraftExtension(minecraftHost, settingsRegistry,
                new MinecraftProvidersExtension(MinecraftFrameAdapter.INSTANCE::installFrameSelector,
                        MinecraftFrameAdapter.INSTANCE::installFrameCapture,
                        new MinecraftClientMaterialEpochCompiler(calibration), calibration,
                        MinecraftFrameAdapter.INSTANCE.entities(),
                        MinecraftFrameAdapter.INSTANCE.entityTextures(),
                        MinecraftFrameAdapter.INSTANCE.entities()));

        Path gameDirectory = platform.gameDir();
        String configuredSlangPath = CausticaConfig.Slang.PATH.get();
        Optional<Path> slangOverride = configuredSlangPath == null || configuredSlangPath.isBlank()
                ? Optional.empty() : Optional.of(Path.of(configuredSlangPath));
        SlangRuntime slangRuntime = new SlangRuntime(new SlangRuntimeConfig(
                gameDirectory.resolve("caustica-slang"), slangOverride));
        var shaderCache = gameDirectory.resolve("caustica-shaders");
        Path shaderCacheRoot = shaderCache.resolve("sources").toAbsolutePath().normalize();
        telemetry.configure(gameDirectory.resolve("rt-frame-stats"), MinecraftFrameMetrics.schema());
        CausticaOptions options = CausticaOptions.load(
                platform.configDir().resolve("caustica-options.toml"),
                settingsRegistry);
        CausticaSettings.initialize(settingsRegistry, options);

        CausticaMod.LOGGER.info("Caustica extension API {} initialized with {} settings feature(s)",
                dev.comfyfluffy.caustica.api.CausticaApi.VERSION, settingsRegistry.all().size());
        String configuredNgxPath = CausticaConfig.Ngx.PATH.get();
        Optional<Path> ngxOverride = configuredNgxPath == null || configuredNgxPath.isBlank()
                ? Optional.empty() : Optional.of(Path.of(configuredNgxPath));
        return new ApiServices(host, minecraftHost, settingsRegistry, slangRuntime, shaderCacheRoot,
                new NgxRuntime.Settings(gameDirectory.resolve("caustica-ngx"), ngxOverride));
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

    public record ApiServices(RenderSessionHost renderSessionHost,
                              MinecraftWorldSessionHost minecraftWorldSessionHost,
                              SettingsRegistry settings,
                              SlangRuntime slangRuntime,
                              Path shaderCacheRoot,
                              NgxRuntime.Settings ngxSettings) { }
}
