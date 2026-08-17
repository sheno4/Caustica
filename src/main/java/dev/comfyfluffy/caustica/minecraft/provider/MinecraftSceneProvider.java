package dev.comfyfluffy.caustica.minecraft.provider;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.SceneFrameContext;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryUpdateContext;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.provider.MaterialSnapshot;
import dev.comfyfluffy.caustica.api.provider.TextureSink;
import dev.comfyfluffy.caustica.minecraft.entity.RtEntities;
import dev.comfyfluffy.caustica.minecraft.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.terrain.RtWorkerPool;
import net.minecraft.client.renderer.texture.TextureAtlas;

import java.util.function.Consumer;

public final class MinecraftSceneProvider implements SceneProvider {
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft_scene");
    private static final Consumer<SceneGeometrySink> TERRAIN_GEOMETRY = RtTerrain::submitGeometry;
    private static final Consumer<SceneFrameContext> ENTITY_GEOMETRY = RtEntities.INSTANCE::submitGeometry;

    @Override
    public void update(SceneGeometryUpdateContext update) {
        RtTerrain.update();
        RtTerrain.submitGeometry(update.geometry());
    }

    @Override
    public void prepareFrame() {
        RtTerrain.frame();
    }

    @Override
    public void onWorldChanged() {
        RtEntities.INSTANCE.onWorldChanged();
        RtTerrain.requestFullClear();
    }

    @Override
    public void onResourcePackClosing() {
        RtEntities.INSTANCE.onResourceReload();
        RtEntityTextures.INSTANCE.reset();
    }

    @Override
    public void stop() {
        stopSources(RtEntities.INSTANCE::onSourceStopped, RtWorkerPool.INSTANCE::shutdown);
    }

    static void stopSources(Runnable stopEntities, Runnable stopWorkers) {
        stopEntities.run();
        stopWorkers.run();
    }

    @Override
    public void shutdown() {
        RtTerrain.shutdown();
        RtEntities.INSTANCE.shutdown();
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
    public void submitTextures(TextureSink textures) {
        RtEntityTextures.INSTANCE.contributeAtlas(TextureAtlas.LOCATION_BLOCKS);
        RtEntityTextures.INSTANCE.submitPending(textures);
    }

    @Override
    public void onMaterialEpoch(MaterialSnapshot materials) {
        RtTerrain.publishMaterials(materials);
    }

    @Override
    public void onMaterialEpochClosing() {
        RtTerrain.clearMaterials();
    }

}
