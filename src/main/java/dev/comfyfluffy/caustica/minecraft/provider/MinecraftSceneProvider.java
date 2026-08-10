package dev.comfyfluffy.caustica.minecraft.provider;

import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;

public final class MinecraftSceneProvider implements SceneProvider {
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
    public void invalidate() {
        RtTerrain.requestFullClear();
    }

    @Override
    public void onResourceReload() {
        RtEntities.INSTANCE.onResourceReload();
    }

    @Override
    public void shutdown() {
        GpuContext ctx = GpuContext.currentOrNull();
        if (ctx != null) {
            RtTerrain.shutdown(ctx);
            RtEntities.INSTANCE.shutdown();
        }
    }
}
