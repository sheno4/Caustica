package dev.comfyfluffy.caustica.minecraft.client.terrain;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialLookup;
import dev.comfyfluffy.caustica.minecraft.rendering.program.MinecraftPrograms;
import dev.comfyfluffy.caustica.minecraft.rendering.terrain.MinecraftTerrainGeometry;
import dev.comfyfluffy.caustica.minecraft.rendering.terrain.MinecraftVulkanTerrainUploader;

/** Session-owned construction hook for the retained terrain producer. */
public final class MinecraftTerrainSession {
    private final GpuDevice gpu;
    private final RtTerrain terrain;
    private MinecraftTerrainGeometry geometry;

    public MinecraftTerrainSession(GpuDevice gpu, RtTerrain terrain) {
        this.gpu = java.util.Objects.requireNonNull(gpu, "gpu");
        this.terrain = java.util.Objects.requireNonNull(terrain, "terrain");
    }

    /**
     * Hands the terrain workers the same immutable lookup whose records the program resource epoch uploads.
     * Section material indices are valid only against that matching epoch.
     */
    public void publishMaterialLookup(MinecraftMaterialLookup lookup) {
        terrain.publishMaterialLookup(lookup);
    }

    /** Suspends new section extraction while resource-pack material records are replaced. */
    public void clearMaterialLookup() {
        terrain.clearMaterialLookup();
    }

    /** Installs the atomic program exports and begins targeting the borrowed world scene. */
    public void bind(MinecraftPrograms programs, GeometryChannel channel, SceneId scene) {
        if (geometry != null) throw new IllegalStateException("terrain session is already bound");
        var uploader = new MinecraftVulkanTerrainUploader(gpu, programs);
        geometry = new MinecraftTerrainGeometry(channel, scene, uploader);
        terrain.bindGeometry(geometry);
    }

    /** Stops publication and atomically removes every retained terrain section. */
    public void stop() {
        terrain.clearMaterialLookup();
        if (geometry == null) return;
        geometry.close();
        terrain.unbindGeometry(geometry);
        geometry = null;
    }

}
