package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.minecraft.MinecraftApiBootstrap;
import dev.comfyfluffy.caustica.minecraft.MinecraftFrameAdapter;
import dev.comfyfluffy.caustica.minecraft.MinecraftRuntimeHost;
import dev.comfyfluffy.caustica.minecraft.MinecraftUiOverlay;
import dev.comfyfluffy.caustica.minecraft.vulkan.MinecraftDeviceBringup;
import dev.comfyfluffy.caustica.minecraft.vulkan.MinecraftVulkanBackend;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.terrain.RtWorkerPool;
import dev.comfyfluffy.caustica.rt.RtRuntime;

import java.util.Objects;

/** Process-owned client services published for loader and mixin hooks after common initialization. */
public final class CausticaClientComposition {
    private static CausticaClientComposition current;

    private final RtRuntime runtime;
    private final MinecraftApiBootstrap.ApiServices apiServices;
    private final MinecraftFrameAdapter frameAdapter;
    private final MinecraftRuntimeHost runtimeHost;
    private final MinecraftUiOverlay uiOverlay;
    private final VanillaRenderController renderController;
    private final WorldRenderScaler renderScaler;
    private final MinecraftDeviceBringup deviceBringup;
    private final MinecraftVulkanBackend vulkanBackend;
    private final RtWorkerPool terrainWorkers;
    private final RtTerrain terrain;

    public CausticaClientComposition(RtRuntime runtime, MinecraftApiBootstrap.ApiServices apiServices,
                                     MinecraftFrameAdapter frameAdapter, MinecraftRuntimeHost runtimeHost,
                                     MinecraftUiOverlay uiOverlay,
                                     VanillaRenderController renderController, WorldRenderScaler renderScaler,
                                     MinecraftDeviceBringup deviceBringup, MinecraftVulkanBackend vulkanBackend,
                                     RtWorkerPool terrainWorkers, RtTerrain terrain) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.apiServices = Objects.requireNonNull(apiServices, "apiServices");
        this.frameAdapter = Objects.requireNonNull(frameAdapter, "frameAdapter");
        this.runtimeHost = Objects.requireNonNull(runtimeHost, "runtimeHost");
        this.uiOverlay = Objects.requireNonNull(uiOverlay, "uiOverlay");
        this.renderController = Objects.requireNonNull(renderController, "renderController");
        this.renderScaler = Objects.requireNonNull(renderScaler, "renderScaler");
        this.deviceBringup = Objects.requireNonNull(deviceBringup, "deviceBringup");
        this.vulkanBackend = Objects.requireNonNull(vulkanBackend, "vulkanBackend");
        this.terrainWorkers = Objects.requireNonNull(terrainWorkers, "terrainWorkers");
        this.terrain = Objects.requireNonNull(terrain, "terrain");
    }

    public RtRuntime runtime() {
        return runtime;
    }

    public MinecraftApiBootstrap.ApiServices apiServices() {
        return apiServices;
    }

    public MinecraftFrameAdapter frameAdapter() { return frameAdapter; }
    public MinecraftRuntimeHost runtimeHost() { return runtimeHost; }
    public MinecraftUiOverlay uiOverlay() { return uiOverlay; }
    public VanillaRenderController renderController() { return renderController; }
    public WorldRenderScaler renderScaler() { return renderScaler; }
    public MinecraftDeviceBringup deviceBringup() { return deviceBringup; }
    public MinecraftVulkanBackend vulkanBackend() { return vulkanBackend; }
    public RtWorkerPool terrainWorkers() { return terrainWorkers; }
    public RtTerrain terrain() { return terrain; }

    /** Advances client integration after the renderer backend has observed Minecraft's live device. */
    public void tickRuntime(net.minecraft.client.Minecraft client) {
        vulkanBackend.installCurrent();
        frameAdapter.tickRuntime(client);
    }

    public static synchronized void publish(CausticaClientComposition composition) {
        if (current != null) {
            throw new IllegalStateException("Caustica client composition is already published");
        }
        current = Objects.requireNonNull(composition, "composition");
    }

    public static synchronized CausticaClientComposition current() {
        if (current == null) {
            throw new IllegalStateException("Caustica client composition is not published");
        }
        return current;
    }
}
