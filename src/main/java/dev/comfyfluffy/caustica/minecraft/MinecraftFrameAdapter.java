package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.frame.SceneResources;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.entity.RtEntities;
import dev.comfyfluffy.caustica.minecraft.entity.RtEntityTextures;
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
    private static final double METERS_PER_WORLD_UNIT = 1.0;
    private final RtTerrain terrain;
    private ClientLevel identifiedLevel;
    private long nextSceneId;
    private long sceneId;
    private volatile MinecraftFrameSelector frameSelector;
    private volatile FrameCaptureBinding frameCapture;
    private final RtEntityTextures entityTextures = new RtEntityTextures();
    private final RtEntities entities;
    private long renderedWorldFrameIndex;

    public MinecraftFrameAdapter(RtTerrain terrain, MinecraftTelemetry.Instrumentation instrumentation) {
        this.terrain = java.util.Objects.requireNonNull(terrain, "terrain");
        entities = new RtEntities(entityTextures,
                java.util.Objects.requireNonNull(instrumentation, "instrumentation"));
    }

    public void tickRuntime(Minecraft client) {
        terrain.update();
        ClientLevel level = client.level;
        long currentSceneId = identify(level);
        if (!(client.gui.overlay() instanceof LoadingOverlay)) {
            CausticaClientComposition.current().runtime().observeResourcePackAvailable();
        }
        var target = client.gameRenderer.mainRenderTarget();
        boolean startupSceneReady = level != null && client.player != null
                && terrain.isSectionReady(client.player.blockPosition());
        CausticaClientComposition.current().runtime().tick(captureSceneResources(client), startupSceneReady, currentSceneId,
                dimensionKey(level),
                target != null ? target.width : 0, target != null ? target.height : 0,
                client::invalidateSurfaceConfiguration);
    }

    /** Returns no snapshot while a world/program epoch has not installed its engine-issued scene. */
    public FrameSnapshot capture(Minecraft client, Matrix4fc projection, Matrix4fc viewRotation,
                                 double cameraX, double cameraY, double cameraZ) {
        terrain.frame();
        ClientLevel level = client.level;
        BlockPos cameraBlockPos = new BlockPos(Mth.floor(cameraX), Mth.floor(cameraY), Mth.floor(cameraZ));
        boolean submerged = false;
        if (level != null) {
            FluidState fluid = level.getFluidState(cameraBlockPos);
            submerged = fluid.is(FluidTags.WATER)
                    && cameraY < cameraBlockPos.getY() + fluid.getHeight(level, cameraBlockPos);
        }
        FrameCaptureBinding capture = frameCapture;
        if (capture != null) {
            capture.sink.update(MinecraftClientFrameCapture.capture(
                    client, cameraY, METERS_PER_WORLD_UNIT, capture.calibration,
                    terrain.retainedLightSnapshot()));
        }
        MinecraftFrameSelector.Selection selection = selection(submerged);
        if (selection == null) return null;
        Camera camera = new Camera(cameraX, cameraY, cameraZ,
                projection.get(new float[16]), viewRotation.get(new float[16]));
        RtTerrain currentTerrain = terrain.currentOrNull();
        SceneOrigin sceneOrigin = currentTerrain != null ? currentTerrain.sceneOrigin() : SceneOrigin.ZERO;
        FrameSnapshot snapshot = new FrameSnapshot(new SceneView(selection.scene(), camera, selection.medium()), sceneOrigin,
                CausticaConfig.Rt.Composite.WATER_WAVES.value(),
                System.nanoTime() / 1.0e9, METERS_PER_WORLD_UNIT);
        entities.submitFrame(snapshot.sceneOrigin(), cameraX, cameraY, cameraZ,
                new org.joml.Matrix4f(projection), new org.joml.Matrix4f(viewRotation),
                renderedWorldFrameIndex++);
        return snapshot;
    }

    RtEntities entities() { return entities; }
    RtEntityTextures entityTextures() { return entityTextures; }

    MinecraftFrameSelectionInstaller.Lease installFrameSelector(MinecraftFrameSelector selector) {
        java.util.Objects.requireNonNull(selector, "selector");
        frameSelector = selector;
        return () -> {
            if (frameSelector == selector) frameSelector = null;
        };
    }

    MinecraftFrameSelector.Selection selection(boolean submerged) {
        MinecraftFrameSelector selector = frameSelector;
        return selector == null ? null : selector.select(submerged);
    }

    MinecraftFrameCaptureInstaller.Lease installFrameCapture(MinecraftFrameCaptureInstaller.Sink sink,
                                                              MinecraftLightingCalibration calibration) {
        java.util.Objects.requireNonNull(sink, "sink");
        FrameCaptureBinding binding = new FrameCaptureBinding(sink,
                java.util.Objects.requireNonNull(calibration, "calibration"));
        frameCapture = binding;
        return () -> {
            if (frameCapture == binding) frameCapture = null;
        };
    }

    private record FrameCaptureBinding(MinecraftFrameCaptureInstaller.Sink sink,
                                       MinecraftLightingCalibration calibration) { }

    public SceneResources captureSceneResources(Minecraft client) {
        if (client.level == null) {
            return SceneResources.EMPTY;
        }
        return new SceneResources(true);
    }

    private long identify(ClientLevel level) {
        if (level != identifiedLevel) {
            identifiedLevel = level;
            sceneId = level == null ? 0L : ++nextSceneId;
        }
        return sceneId;
    }

    private static MinecraftDimensionKey dimensionKey(ClientLevel level) {
        if (level == null) return null;
        var id = level.dimension().identifier();
        return new MinecraftDimensionKey(ResourceId.of(id.getNamespace(), id.getPath()));
    }
}
