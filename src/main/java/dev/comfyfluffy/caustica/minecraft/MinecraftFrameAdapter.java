package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.frame.SceneResources;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.rt.RtRuntime;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.vulkan.MinecraftVulkanBackend;
import dev.comfyfluffy.caustica.settings.ResourceId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4fc;



/** Converts Minecraft lifecycle and camera state into coherent engine frame inputs. */
public final class MinecraftFrameAdapter {
    public static final MinecraftFrameAdapter INSTANCE = new MinecraftFrameAdapter();

    private static final double METERS_PER_WORLD_UNIT = 1.0;
    private ClientLevel identifiedLevel;
    private SceneId fallbackScene;
    private long nextSceneId;
    private long sceneId;

    private MinecraftFrameAdapter() {
    }

    public void tickRuntime(Minecraft client) {
        MinecraftVulkanBackend.installCurrent();
        ClientLevel level = client.level;
        long currentSceneId = identify(level);
        if (!(client.gui.overlay() instanceof LoadingOverlay)) {
            RtRuntime.INSTANCE.observeResourcePackAvailable();
        }
        var target = client.gameRenderer.mainRenderTarget();
        boolean startupSceneReady = level != null && client.player != null
                && RtTerrain.isSectionReady(client.player.blockPosition());
        RtRuntime.INSTANCE.tick(captureSceneResources(client), startupSceneReady, currentSceneId,
                dimensionKey(level),
                target != null ? target.width : 0, target != null ? target.height : 0,
                client::invalidateSurfaceConfiguration);
    }

    public FrameSnapshot capture(Minecraft client, Matrix4fc projection, Matrix4fc viewRotation,
                                 double cameraX, double cameraY, double cameraZ) {
        ClientLevel level = client.level;
        BlockPos cameraBlockPos = new BlockPos(Mth.floor(cameraX), Mth.floor(cameraY), Mth.floor(cameraZ));
        boolean submerged = false;
        if (level != null) {
            FluidState fluid = level.getFluidState(cameraBlockPos);
            submerged = fluid.is(FluidTags.WATER)
                    && cameraY < cameraBlockPos.getY() + fluid.getHeight(level, cameraBlockPos);
        }
        MinecraftFrameSelector.Selection selection = MinecraftFrameSelector.select(submerged);
        SceneId scene = selection != null ? selection.scene() : fallbackScene(level);
        Camera camera = new Camera(cameraX, cameraY, cameraZ,
                projection.get(new float[16]), viewRotation.get(new float[16]));
        RtTerrain terrain = RtTerrain.currentOrNull();
        SceneOrigin sceneOrigin = terrain != null ? terrain.sceneOrigin() : SceneOrigin.ZERO;
        return new FrameSnapshot(new SceneView(scene, camera), sceneOrigin,
                selection != null ? selection.initialVolume() : null,
                CausticaConfig.Rt.Composite.WATER_WAVES.value(),
                System.nanoTime() / 1.0e9, METERS_PER_WORLD_UNIT);
    }

    public SceneResources captureSceneResources(Minecraft client) {
        if (client.level == null) {
            return SceneResources.EMPTY;
        }
        return new SceneResources(true);
    }

    public UiPresentationResources captureUiPresentation() {
        return snapshotUiPresentation(MinecraftUiOverlay.enabled(),
                MinecraftUiOverlay.populatedThisFrame(),
                MinecraftUiOverlay.presentationImage(),
                MinecraftUiOverlay.overlayWidth(), MinecraftUiOverlay.overlayHeight());
    }

    static UiPresentationResources snapshotUiPresentation(boolean enabled, boolean populated,
                                                           dev.comfyfluffy.caustica.api.vulkan.GpuImage color,
                                                           int width, int height) {
        return new UiPresentationResources(enabled, populated, color, width, height);
    }

    private long identify(ClientLevel level) {
        if (level != identifiedLevel) {
            identifiedLevel = level;
            fallbackScene = level == null ? null : new SceneId() {};
            sceneId = level == null ? 0L : ++nextSceneId;
        }
        return sceneId;
    }

    private SceneId fallbackScene(ClientLevel level) {
        identify(level);
        if (fallbackScene == null) fallbackScene = new SceneId() {};
        return fallbackScene;
    }

    private static MinecraftDimensionKey dimensionKey(ClientLevel level) {
        if (level == null) return null;
        var id = level.dimension().identifier();
        return new MinecraftDimensionKey(ResourceId.of(id.getNamespace(), id.getPath()));
    }
}
