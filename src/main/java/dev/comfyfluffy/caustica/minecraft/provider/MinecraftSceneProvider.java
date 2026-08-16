package dev.comfyfluffy.caustica.minecraft.provider;

import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.provider.SceneFrameContext;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryUpdateContext;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.minecraft.entity.RtEntities;
import dev.comfyfluffy.caustica.minecraft.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.scene.RtSceneSource;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.terrain.RtWorkerPool;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;

public final class MinecraftSceneProvider implements SceneProvider, RtSceneSource {
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft_scene");
    private static final Consumer<SceneGeometrySink> TERRAIN_GEOMETRY =
            sink -> RtTerrain.submitFrameGeometry(GpuContext.currentOrNull(), sink);
    private static final Consumer<SceneFrameContext> ENTITY_GEOMETRY = RtEntities.INSTANCE::submitGeometry;

    @Override
    public void update() {
        GpuContext ctx = GpuContext.currentOrNull();
        if (ctx != null) {
            RtTerrain.update(ctx);
        }
    }

    @Override
    public void prepareFrame() {
        GpuContext ctx = GpuContext.currentOrNull();
        if (ctx != null) {
            RtTerrain.frame(ctx);
        }
    }

    @Override
    public void onWorldChanged() {
        GpuContext ctx = GpuContext.currentOrNull();
        if (ctx != null) {
            RtEntities.INSTANCE.onWorldChanged();
        }
        RtTerrain.requestFullClear();
    }

    @Override
    public void onResourcePackClosing() {
        RtEntities.INSTANCE.onResourceReload();
    }

    @Override
    public void stop() {
        RtWorkerPool.INSTANCE.shutdown();
    }

    @Override
    public void shutdown() {
        GpuContext ctx = GpuContext.currentOrNull();
        if (ctx != null) {
            RtTerrain.shutdown(ctx);
            RtEntities.INSTANCE.shutdown();
        }
    }

    @Override
    public Retained retainedScene() {
        RtTerrain terrain = RtTerrain.currentOrNull();
        if (terrain == null) {
            return null;
        }
        SceneOrigin origin = new SceneOrigin(terrain.blockX, terrain.blockY, terrain.blockZ);
        var published = terrain.retainedLights();
        RetainedLights lights = new RetainedLights(
                published.lightAddress(), published.nodeAddress(), published.rootNodeIndex(),
                published.lightCount(), published.lightCount(),
                published.rebaseX() - terrain.blockX,
                published.rebaseY() - terrain.blockY,
                published.rebaseZ() - terrain.blockZ,
                published.metersPerWorldUnit(), published.generation());
        return new Retained(origin, lights);
    }

    @Override
    public void submitGeometry(SceneFrameContext frame) {
        submitFrameGeometry(frame, TERRAIN_GEOMETRY, ENTITY_GEOMETRY);
    }

    static void submitFrameGeometry(SceneFrameContext frame, Consumer<SceneGeometrySink> terrain,
                                    Consumer<SceneFrameContext> entities) {
        // prepareFrame produces terrain groups immediately before this callback. Drain them through the
        // same frame sink so the manager can start their BLAS work before assembling this frame's TLAS.
        terrain.accept(frame.geometry());
        entities.accept(frame);
    }

    @Override
    public void submitGeometryUpdates(SceneGeometryUpdateContext update) {
        RtTerrain.submitGeometry(update.geometry());
    }

    @Override
    public int bindlessTextureCapacity() {
        return RtEntityTextures.BINDLESS_CAPACITY;
    }

    @Override
    public void resetBindlessTextures(int capacity) {
        RtEntityTextures.INSTANCE.reset(capacity);
    }

    @Override
    public void rebindTextures(RtPipeline pipeline, long sampler) {
        RtEntityTextures.INSTANCE.rebindAll(pipeline, sampler);
    }

    @Override
    public void uploadPendingTextures(RtPipeline pipeline, long sampler) {
        RtEntityTextures.INSTANCE.uploadPending(pipeline, sampler);
    }

    @Override
    public int bindlessTextureSlot(SceneMesh.TextureReference texture) {
        return switch (texture) {
            case SceneMesh.AtlasTexture atlas -> RtEntityTextures.INSTANCE.isWhiteTexture(atlas.atlas())
                    ? RtEntityTextures.INSTANCE.whiteSlot()
                    : RtEntityTextures.INSTANCE.slotForAtlas(identifier(atlas.atlas()));
            case SceneMesh.StandaloneTexture standalone -> RtEntityTextures.INSTANCE.slotForAtlas(
                    Identifier.fromNamespaceAndPath(standalone.texture().namespace(),
                            "textures/" + standalone.texture().path() + ".png"));
        };
    }

    private static Identifier identifier(dev.comfyfluffy.caustica.api.ResourceId id) {
        return Identifier.fromNamespaceAndPath(id.namespace(), id.path());
    }
}
