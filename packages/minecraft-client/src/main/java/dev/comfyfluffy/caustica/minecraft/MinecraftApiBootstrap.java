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
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import dev.comfyfluffy.caustica.platform.CausticaPlatform;
import dev.comfyfluffy.caustica.rt.RtTelemetry;
import dev.comfyfluffy.caustica.nvidia.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.settings.CausticaSettingsExtension;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.slang.SlangRuntimeConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;

/** Loader-neutral process extension discovery and settings installation. */
public final class MinecraftApiBootstrap {
    private MinecraftApiBootstrap() { }

    public static ApiServices initialize(CausticaPlatform platform, RtTelemetry telemetry,
                                         MinecraftFrameAdapter frameAdapter, RtTerrain terrain) {
        SettingsRegistry settingsRegistry = new SettingsRegistry();
        List<CausticaExtension> extensions = new ArrayList<>();
        extensions.add(new BuiltinExtension());
        extensions.addAll(platform.extensions());
        MinecraftLightingCalibration calibration = MinecraftLightingCalibrationLoader.loadDefault();
        MinecraftProvidersExtension minecraftProviders = new MinecraftProvidersExtension(
                frameAdapter::installFrameSelector, frameAdapter::installFrameCapture,
                new MinecraftClientMaterialEpochCompiler(calibration), calibration,
                frameAdapter.entities(), frameAdapter.entityTextures(), frameAdapter.entities(), terrain,
                MinecraftTelemetry.renderer(telemetry));
        List<MinecraftExtension> minecraftExtensions = new ArrayList<>(platform.minecraftExtensions());
        minecraftExtensions.add(minecraftProviders);

        registerSettings(settingsRegistry, extensions, minecraftExtensions);

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
        RenderSessionHost host = new RenderSessionHost(options);
        MinecraftWorldSessionHost minecraftHost = new MinecraftWorldSessionHost(options);
        registerExtensions(host, minecraftHost, extensions);
        registerMinecraftExtensions(minecraftHost, minecraftExtensions, extensions);

        CausticaMod.LOGGER.info("Caustica extension API {} initialized with {} settings feature(s)",
                dev.comfyfluffy.caustica.api.CausticaApi.VERSION, settingsRegistry.all().size());
        String configuredNgxPath = CausticaConfig.Ngx.PATH.get();
        Optional<Path> ngxOverride = configuredNgxPath == null || configuredNgxPath.isBlank()
                ? Optional.empty() : Optional.of(Path.of(configuredNgxPath));
        return new ApiServices(host, minecraftHost, settingsRegistry, options, slangRuntime, shaderCacheRoot,
                new NgxRuntime.Settings(gameDirectory.resolve("caustica-ngx"), ngxOverride));
    }

    static void registerMinecraftExtensions(MinecraftWorldSessionHost host,
                                            List<MinecraftExtension> extensions,
                                            List<CausticaExtension> genericExtensions) {
        for (MinecraftExtension extension : extensions) {
            if (genericExtensions.stream().anyMatch(generic -> generic == extension)) continue;
            registerMinecraftSessionExtension(host, extension);
        }
    }

    static void registerExtensions(RenderSessionHost host, MinecraftWorldSessionHost minecraftHost,
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
        }
    }

    static void registerSettings(SettingsRegistry settingsRegistry,
                                 List<? extends CausticaExtension> genericExtensions,
                                 List<? extends MinecraftExtension> minecraftExtensions) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (Object extension : genericExtensions) {
            registerSettings(settingsRegistry, extension, seen);
        }
        for (Object extension : minecraftExtensions) {
            registerSettings(settingsRegistry, extension, seen);
        }
    }

    private static void registerSettings(SettingsRegistry settingsRegistry, Object extension, java.util.Set<Object> seen) {
        if (!seen.add(extension) || !(extension instanceof CausticaSettingsExtension settingsExtension)) return;
        try {
            settingsExtension.registerSettings(settingsRegistry);
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
                              CausticaOptions options,
                              SlangRuntime slangRuntime,
                              Path shaderCacheRoot,
                              NgxRuntime.Settings ngxSettings) { }
}
