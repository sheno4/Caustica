package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftApiBootstrap;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftFrameAdapter;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftRuntimeHost;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftTelemetry;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftUiOverlay;
import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.minecraft.client.VanillaRenderController;
import dev.comfyfluffy.caustica.minecraft.client.WorldRenderScaler;
import dev.comfyfluffy.caustica.minecraft.client.vulkan.MinecraftDeviceBringup;
import dev.comfyfluffy.caustica.minecraft.client.vulkan.MinecraftVulkanBackend;
import dev.comfyfluffy.caustica.minecraft.client.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.client.terrain.RtWorkerPool;
import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.platform.CausticaPlatform;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftRtRuntime;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CausticaMod {
    public static final String MOD_ID = "caustica";
    public static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    private CausticaMod() {
    }

    public static void initialize(CausticaPlatform platform) {
        CausticaConfig.configure(platform.configDir());
        // Register every setting (applying TOML file values) and write a default config on first run.
        CausticaConfig.ensureRegistered();
        CausticaConfig.saveIfMissing();
        RtTelemetryImpl telemetry = new RtTelemetryImpl();
        MinecraftTelemetry.Instrumentation minecraftTelemetry = MinecraftTelemetry.renderer(telemetry);
        RtWorkerPool terrainWorkers = new RtWorkerPool();
        RtTerrain terrain = new RtTerrain(terrainWorkers, minecraftTelemetry);
        MinecraftFrameAdapter frameAdapter = new MinecraftFrameAdapter(terrain, minecraftTelemetry);
        MinecraftApiBootstrap.ApiServices apiServices = MinecraftApiBootstrap.initialize(
                platform, telemetry, frameAdapter, terrain);
        MinecraftRtRuntime runtime = new MinecraftRtRuntime(apiServices.renderSessionHost(), apiServices.minecraftWorldSessionHost(),
                apiServices.slangRuntime(), apiServices.shaderCacheRoot(), telemetry, apiServices.ngxSettings());
        VanillaRenderController renderController = new VanillaRenderController(terrain);
        WorldRenderScaler renderScaler = new WorldRenderScaler(renderController);
        MinecraftUiOverlay uiOverlay = new MinecraftUiOverlay(runtime);
        MinecraftRuntimeHost runtimeHost = new MinecraftRuntimeHost(renderController, renderScaler, uiOverlay);
        MinecraftDeviceBringup deviceBringup = new MinecraftDeviceBringup();
        MinecraftVulkanBackend vulkanBackend = new MinecraftVulkanBackend(deviceBringup, runtime);
        CausticaClientComposition.publish(new CausticaClientComposition(runtime, apiServices, frameAdapter,
                runtimeHost, uiOverlay, renderController, renderScaler, deviceBringup, vulkanBackend,
                terrainWorkers, terrain));
        LOGGER.info("Caustica initialized (common); config: {}", CausticaConfig.configPath());
    }
}
