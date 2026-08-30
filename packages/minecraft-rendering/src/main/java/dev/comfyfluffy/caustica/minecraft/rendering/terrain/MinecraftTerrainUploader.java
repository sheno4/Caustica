package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;

/** GPU upload seam between Minecraft's CPU section extraction and retained engine geometry. */
@FunctionalInterface
public interface MinecraftTerrainUploader extends AutoCloseable {
    UploadedSection upload(MinecraftTerrainMesh source);

    @Override default void close() { }

    /**
     * Source-owned GPU buffers and shader records borrowed by one retained mesh/placement batch.
     * {@link #close()} is called only by retained retirement, or immediately when submission is rejected.
     * It must release everything it owns, return promptly, and never throw.
     */
    interface UploadedSection extends AutoCloseable {
        MeshBuild<MinecraftProgramTypes.InstanceData> build();
        ShaderData<MinecraftProgramTypes.InstanceData> instanceData();
        @Override void close();
    }
}
