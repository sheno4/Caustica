package dev.comfyfluffy.caustica.minecraft.client.terrain;

import dev.comfyfluffy.caustica.api.geometry.MeshPreparer;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialLookup;
import dev.comfyfluffy.caustica.minecraft.rendering.program.MinecraftPrograms;
import dev.comfyfluffy.caustica.minecraft.rendering.terrain.MinecraftTerrainGeometry;
import dev.comfyfluffy.caustica.minecraft.rendering.terrain.MinecraftVulkanTerrainUploader;
import dev.comfyfluffy.caustica.minecraft.client.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import net.minecraft.client.renderer.texture.TextureAtlas;

/** Session-owned construction hook for the retained terrain producer. */
public final class MinecraftTerrainSession {
    private final GpuDevice gpu;
    private final RtTerrain terrain;
    private final RtEntityTextures textures;
    private final ResourceFactory resources;
    private MinecraftTerrainGeometry geometry;

    public MinecraftTerrainSession(GpuDevice gpu, ResourceFactory resources,
                                   RtTerrain terrain, RtEntityTextures textures) {
        this.gpu = java.util.Objects.requireNonNull(gpu, "gpu");
        this.resources = java.util.Objects.requireNonNull(resources, "resources");
        this.terrain = java.util.Objects.requireNonNull(terrain, "terrain");
        this.textures = java.util.Objects.requireNonNull(textures, "textures");
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
    public void bind(MinecraftPrograms programs, MeshPreparer meshes, SceneChannel channel, SceneId scene) {
        if (geometry != null) throw new IllegalStateException("terrain session is already bound");
        var atlas = textures.contributeAtlas(TextureAtlas.LOCATION_BLOCKS);
        var borrowed = java.util.Objects.requireNonNull(textures.resolve(atlas),
                "Minecraft block atlas must be available to terrain");
        var uploader = new MinecraftVulkanTerrainUploader(gpu, programs, borrowed, resources);
        try {
            var next = new MinecraftTerrainGeometry(meshes, channel, scene, uploader);
            terrain.bindGeometry(next);
            geometry = next;
        } catch (RuntimeException | Error failure) {
            ResourceLifetime.closeAfterFailure(failure, uploader::close);
            throw failure;
        }
    }

    /** Stops publication and atomically removes every retained terrain section. */
    public void stop() {
        terrain.clearMaterialLookup();
        if (geometry == null) return;
        MinecraftTerrainGeometry retiring = geometry;
        geometry = null;
        new ResourceLifetime(() -> terrain.unbindGeometry(retiring), retiring::close).close();
    }

}
