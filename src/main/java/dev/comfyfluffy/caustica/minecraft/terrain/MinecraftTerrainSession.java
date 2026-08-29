package dev.comfyfluffy.caustica.minecraft.terrain;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialLookup;
import dev.comfyfluffy.caustica.minecraft.program.MinecraftPrograms;

/** Session-owned construction hook for the retained terrain producer. */
public final class MinecraftTerrainSession {
    private final GpuDevice gpu;
    private MinecraftTerrainGeometry geometry;

    public MinecraftTerrainSession(GpuDevice gpu) {
        this.gpu = java.util.Objects.requireNonNull(gpu, "gpu");
    }

    /**
     * Hands the terrain workers the same immutable lookup whose records the program resource epoch uploads.
     * Section material indices are valid only against that matching epoch.
     */
    public void publishMaterialLookup(MinecraftMaterialLookup lookup) {
        RtTerrain.publishMaterialLookup(lookup);
    }

    /** Suspends new section extraction while resource-pack material records are replaced. */
    public void clearMaterialLookup() {
        RtTerrain.clearMaterialLookup();
    }

    /** Installs the atomic program exports and begins targeting the borrowed world scene. */
    public void bind(MinecraftPrograms programs, GeometryChannel channel, SceneId scene) {
        if (geometry != null) throw new IllegalStateException("terrain session is already bound");
        var uploader = new MinecraftVulkanTerrainUploader(gpu, programs);
        geometry = new MinecraftTerrainGeometry(channel, scene, uploader);
        RtTerrain.bindGeometry(geometry);
    }

    /** Stops publication and atomically removes every retained terrain section. */
    public void stop() {
        RtTerrain.clearMaterialLookup();
        if (geometry == null) return;
        geometry.close();
        RtTerrain.unbindGeometry(geometry);
        geometry = null;
    }

}
