package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameCaptureInstaller;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameSelectionInstaller;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameSelector;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;

import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.frame.SceneResources;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.client.terrain.MinecraftFluidSurface;
import dev.comfyfluffy.caustica.minecraft.client.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.client.entity.RtEntities;
import dev.comfyfluffy.caustica.minecraft.client.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.settings.ResourceId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Converts Minecraft lifecycle and camera state into coherent engine frame inputs. */
public final class MinecraftFrameAdapter {
    private static final double METERS_PER_WORLD_UNIT = 1.0;
    private final RtTerrain terrain;
    private ClientLevel identifiedLevel;
    private long nextSceneId;
    private long sceneId;
    private final AtomicReference<FrameSelectionBinding> frameSelector = new AtomicReference<>();
    private final AtomicReference<FrameCaptureBinding> frameCapture = new AtomicReference<>();
    private final RtEntityTextures entityTextures = new RtEntityTextures();
    private final RtEntities entities;
    private long renderedWorldFrameIndex;
    private final MinecraftFogCapture fog = new MinecraftFogCapture();

    public MinecraftFrameAdapter(RtTerrain terrain, MinecraftTelemetry.Instrumentation instrumentation) {
        this.terrain = Objects.requireNonNull(terrain, "terrain");
        entities = new RtEntities(entityTextures,
                Objects.requireNonNull(instrumentation, "instrumentation"));
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
    public FrameSnapshot capture(Minecraft client, Matrix4fc baseProjection, Matrix4fc levelProjection,
                                 Matrix4fc viewRotation,
                                 double cameraX, double cameraY, double cameraZ) {
        try (var ignored = CausticaClientComposition.current().runtime().profileStage("host.frameCapture")) {
            terrain.frame();
            Camera camera = centerLevelCamera(baseProjection, levelProjection, viewRotation,
                    cameraX, cameraY, cameraZ);
            ClientLevel level = client.level;
            BlockPos cameraBlockPos = new BlockPos(
                    Mth.floor(camera.x()), Mth.floor(camera.y()), Mth.floor(camera.z()));
            boolean submerged = false;
            if (level != null) {
                BlockState blockState = level.getBlockState(cameraBlockPos);
                FluidState fluid = blockState.getFluidState();
                if (fluid.is(FluidTags.WATER)) {
                    submerged = MinecraftFluidSurface.contains(
                            level, cameraBlockPos, blockState, fluid, camera.x(), camera.y(), camera.z());
                }
            }
            FrameCaptureBinding capture = frameCapture.get();
            if (capture != null) {
                dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFogFrame.Grid fogGrid;
                try (var fogScope = CausticaClientComposition.current().runtime().profileStage("host.fogCapture")) {
                    fogGrid = level != null && net.minecraft.world.level.Level.OVERWORLD.equals(level.dimension())
                            ? fog.capture(level, camera.x(), camera.z()) : null;
                }
                if (fogGrid == null && (level == null
                        || !net.minecraft.world.level.Level.OVERWORLD.equals(level.dimension()))) fog.clear();
                capture.sink.update(MinecraftClientFrameCapture.capture(
                        client, camera.y(), METERS_PER_WORLD_UNIT, capture.calibration, fogGrid));
            }
            MinecraftFrameSelector.Selection selection = selection(submerged);
            if (selection == null) return null;
            RtTerrain currentTerrain = terrain.currentOrNull();
            SceneOrigin sceneOrigin = currentTerrain != null ? currentTerrain.sceneOrigin() : SceneOrigin.ZERO;
            SceneView view;
            try (var fogScope = CausticaClientComposition.current().runtime().profileStage("host.fogPrepare")) {
                view = sceneView(selection, camera, sceneOrigin,
                        capture == null ? null : capture.sink, METERS_PER_WORLD_UNIT);
            }
            FrameSnapshot snapshot = new FrameSnapshot(view, sceneOrigin,
                    CausticaConfig.get(MinecraftOptions.Rt.Composite.WATER_WAVES),
                    System.nanoTime() / 1.0e9, METERS_PER_WORLD_UNIT);
            entities.submitFrame(snapshot.sceneOrigin(), camera.x(), camera.y(), camera.z(),
                    new Matrix4f().set(camera.clipFromView()), new Matrix4f(viewRotation),
                    renderedWorldFrameIndex++);
            return snapshot;
        }
    }

    static SceneView sceneView(MinecraftFrameSelector.Selection selection, Camera camera, SceneOrigin origin,
                               MinecraftFrameCaptureInstaller.Sink sink, double metersPerSceneUnit) {
        var spatial = sink == null ? null : sink.spatialMedium(camera,
                origin.x(), origin.y(), origin.z(), metersPerSceneUnit);
        return new SceneView(selection.scene(), camera, selection.medium(), spatial);
    }

    /**
     * Moves the affine view translation embedded in Minecraft's level projection into the camera position.
     * The resulting projection-view matrix represents exactly the same clip transform, but its inverse rays
     * originate at the camera position as required by the RT primary-ray shader.
     */
    static Camera centerLevelCamera(Matrix4fc baseProjection, Matrix4fc levelProjection,
                                    Matrix4fc viewRotation,
                                    double cameraX, double cameraY, double cameraZ) {
        Matrix4f viewEffect = new Matrix4f(baseProjection).invert().mul(levelProjection);
        Vector3f originOffset = new Matrix4f(viewEffect).mul(viewRotation).invert()
                .transformPosition(new Vector3f());
        viewEffect.m30(0.0f).m31(0.0f).m32(0.0f);
        Matrix4f centeredProjection = new Matrix4f(baseProjection).mul(viewEffect);
        return new Camera(cameraX + originOffset.x, cameraY + originOffset.y, cameraZ + originOffset.z,
                centeredProjection.get(new float[16]), viewRotation.get(new float[16]));
    }

    RtEntities entities() { return entities; }
    RtEntityTextures entityTextures() { return entityTextures; }

    MinecraftFrameSelectionInstaller.Lease installFrameSelector(MinecraftFrameSelector selector) {
        FrameSelectionBinding binding = new FrameSelectionBinding(Objects.requireNonNull(selector, "selector"));
        frameSelector.set(binding);
        return () -> frameSelector.compareAndSet(binding, null);
    }

    MinecraftFrameSelector.Selection selection(boolean submerged) {
        FrameSelectionBinding binding = frameSelector.get();
        return binding == null ? null : binding.selector.select(submerged);
    }

    MinecraftFrameCaptureInstaller.Lease installFrameCapture(MinecraftFrameCaptureInstaller.Sink sink,
                                                              MinecraftLightingCalibration calibration) {
        Objects.requireNonNull(sink, "sink");
        FrameCaptureBinding binding = new FrameCaptureBinding(sink,
                Objects.requireNonNull(calibration, "calibration"));
        frameCapture.set(binding);
        return () -> frameCapture.compareAndSet(binding, null);
    }

    private record FrameSelectionBinding(MinecraftFrameSelector selector) { }

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
            fog.clear();
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
