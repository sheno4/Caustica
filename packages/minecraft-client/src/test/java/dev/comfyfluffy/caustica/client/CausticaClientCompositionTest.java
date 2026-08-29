package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.engine.session.RenderSessionHost;
import dev.comfyfluffy.caustica.minecraft.MinecraftApiBootstrap;
import dev.comfyfluffy.caustica.minecraft.MinecraftFrameAdapter;
import dev.comfyfluffy.caustica.minecraft.MinecraftRuntimeHost;
import dev.comfyfluffy.caustica.minecraft.MinecraftTelemetry;
import dev.comfyfluffy.caustica.minecraft.MinecraftUiOverlay;
import dev.comfyfluffy.caustica.minecraft.adapter.session.MinecraftWorldSessionHost;
import dev.comfyfluffy.caustica.minecraft.vulkan.MinecraftDeviceBringup;
import dev.comfyfluffy.caustica.minecraft.vulkan.MinecraftVulkanBackend;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.terrain.RtWorkerPool;
import dev.comfyfluffy.caustica.nvidia.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.minecraft.MinecraftRtRuntime;
import dev.comfyfluffy.caustica.rt.RtTelemetryImpl;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import dev.comfyfluffy.caustica.config.CausticaOptions;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.slang.SlangRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CausticaClientCompositionTest {
    @TempDir Path temporaryDirectory;

    @Test
    void publishesOneEagerlyConstructedRootExactlyOnce() {
        SettingsRegistry settings = new SettingsRegistry();
        CausticaOptions options = CausticaOptions.load(temporaryDirectory.resolve("options.toml"), settings);
        RenderSessionHost renderHost = new RenderSessionHost(options);
        MinecraftWorldSessionHost minecraftHost = new MinecraftWorldSessionHost(options);
        SlangRuntime slang = new SlangRuntime(new SlangRuntimeConfig(
                temporaryDirectory.resolve("slang"), Optional.empty()));
        Path shaderCache = temporaryDirectory.resolve("shaders");
        NgxRuntime.Settings ngx = new NgxRuntime.Settings(temporaryDirectory.resolve("ngx"), Optional.empty());
        RtTelemetryImpl telemetry = new RtTelemetryImpl();
        MinecraftApiBootstrap.ApiServices services = new MinecraftApiBootstrap.ApiServices(
                renderHost, minecraftHost, settings, options, slang, shaderCache, ngx);
        MinecraftRtRuntime runtime = new MinecraftRtRuntime(renderHost, minecraftHost, slang, shaderCache, telemetry, ngx);
        RtWorkerPool terrainWorkers = new RtWorkerPool();
        RtTerrain terrain = new RtTerrain(terrainWorkers, MinecraftTelemetry.disabled());
        MinecraftFrameAdapter frameAdapter = new MinecraftFrameAdapter(terrain, MinecraftTelemetry.disabled());
        VanillaRenderController renderController = new VanillaRenderController(terrain);
        WorldRenderScaler renderScaler = new WorldRenderScaler(renderController);
        MinecraftUiOverlay uiOverlay = new MinecraftUiOverlay(runtime);
        MinecraftRuntimeHost runtimeHost = new MinecraftRuntimeHost(renderController, renderScaler, uiOverlay);
        MinecraftDeviceBringup deviceBringup = new MinecraftDeviceBringup();
        MinecraftVulkanBackend vulkanBackend = new MinecraftVulkanBackend(deviceBringup, runtime);
        CausticaClientComposition composition = new CausticaClientComposition(runtime, services, frameAdapter,
                runtimeHost, uiOverlay, renderController, renderScaler, deviceBringup, vulkanBackend,
                terrainWorkers, terrain);

        CausticaClientComposition.publish(composition);

        assertSame(composition, CausticaClientComposition.current());
        assertSame(runtime, CausticaClientComposition.current().runtime());
        assertSame(options, composition.apiServices().options());
        assertSame(frameAdapter, composition.frameAdapter());
        assertSame(runtimeHost, composition.runtimeHost());
        assertSame(uiOverlay, composition.uiOverlay());
        assertSame(renderController, composition.renderController());
        assertSame(renderScaler, composition.renderScaler());
        assertSame(deviceBringup, composition.deviceBringup());
        assertSame(vulkanBackend, composition.vulkanBackend());
        assertSame(terrainWorkers, composition.terrainWorkers());
        assertSame(terrain, composition.terrain());
        assertThrows(IllegalStateException.class,
                () -> CausticaClientComposition.publish(new CausticaClientComposition(runtime, services,
                        frameAdapter, runtimeHost, uiOverlay, renderController, renderScaler, deviceBringup, vulkanBackend,
                        terrainWorkers, terrain)));
    }
}
