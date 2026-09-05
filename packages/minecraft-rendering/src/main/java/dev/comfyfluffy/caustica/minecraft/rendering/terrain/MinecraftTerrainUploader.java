package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;

/** GPU upload seam between Minecraft's CPU section extraction and prepared engine geometry. */
@FunctionalInterface
public interface MinecraftTerrainUploader extends AutoCloseable {
    UploadedSection upload(MinecraftTerrainMesh source);

    @Override default void close() { }

    /** Producer claim on upload buffers and instance data, kept until replacement or removal. */
    interface UploadedSection extends AutoCloseable {
        MeshBuild<MinecraftProgramTypes.InstanceData> build();
        ShaderData<MinecraftProgramTypes.InstanceData> instanceData();
        @Override void close();
    }
}
