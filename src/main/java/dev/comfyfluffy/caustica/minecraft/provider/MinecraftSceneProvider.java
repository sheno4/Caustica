package dev.comfyfluffy.caustica.minecraft.provider;

import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.GraphicsUse;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.minecraft.entity.RtEntities;
import dev.comfyfluffy.caustica.minecraft.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.geometry.RtGeometryAbi;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.scene.RtSceneSource;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.terrain.RtWorkerPool;

import java.util.List;

public final class MinecraftSceneProvider implements SceneProvider, RtSceneSource {
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft_scene");

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
        return new Retained(origin, terrain.staticInstances(), terrain.geometryTablePrefix(), lights);
    }

    @Override
    public Frame beginFrame(GpuContext ctx, Retained retained, List<RtAccel.Instance> baseInstances,
                            RtGeometryAbi.TablePrefix geometryTable, Camera camera) {
        SceneOrigin origin = retained.origin();
        RtEntities.FrameEntities entities = RtEntities.INSTANCE.beginFrame(ctx, baseInstances, geometryTable,
                (int) origin.x(), (int) origin.y(), (int) origin.z(),
                camera.x(), camera.y(), camera.z(), camera.projection(), camera.viewRotation());
        return new MinecraftFrame(entities);
    }

    @Override
    public int bindlessTextureCapacity() {
        return RtEntityTextures.maxTextures();
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

    private record MinecraftFrame(RtEntities.FrameEntities entities) implements Frame {
        @Override
        public List<RtAccel.Instance> dynamicInstances() {
            return entities.dynamicInstances();
        }

        @Override
        public List<RtAccel.PreparedBlas> blasBuilds() {
            return entities.blas();
        }

        @Override
        public long geometryTableAddress() {
            return entities.geometryTableAddress();
        }

        @Override
        public void markGraphicsUse(GraphicsUse graphicsUse) {
            RtEntities.INSTANCE.markGraphicsUse(entities, graphicsUse);
        }
    }
}
