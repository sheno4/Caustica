package dev.comfyfluffy.caustica.minecraft.provider;

import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.minecraft.entity.RtEntities;
import dev.comfyfluffy.caustica.minecraft.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.geometry.RtGeometryAbi;
import dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.provider.ProviderManager;
import dev.comfyfluffy.caustica.rt.scene.RtSceneSource;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.terrain.RtWorkerPool;

public final class MinecraftSceneProvider implements SceneProvider, RtSceneSource {
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft_scene");

    @Override
    public void update() {
        GpuContext ctx = GpuContext.currentOrNull();
        if (ctx != null) {
            RtTerrain.attachGeometry(ProviderManager.INSTANCE.sceneGeometry());
            RtTerrain.update(ctx);
        }
    }

    @Override
    public void prepareFrame() {
        GpuContext ctx = GpuContext.currentOrNull();
        if (ctx != null) {
            RtTerrain.attachGeometry(ProviderManager.INSTANCE.sceneGeometry());
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
            ProviderManager.INSTANCE.sceneGeometry().releasePackedCoordinator();
            RtEntities.INSTANCE.shutdown(ctx);
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
    public void submitFrame(GpuContext ctx, Retained retained, RtSceneGeometryManager.DynamicFrame geometry,
                            Camera camera) {
        SceneOrigin origin = retained.origin();
        RtEntities.INSTANCE.beginFrame(ctx, geometry,
                (int) origin.x(), (int) origin.y(), (int) origin.z(),
                camera.x(), camera.y(), camera.z(), camera.projection(), camera.viewRotation());
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
}
