package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;

/** GPU upload seam for captured entity streams, primitive material records, and texture descriptors. */
public interface MinecraftEntityUploader extends AutoCloseable {
    UploadedEntity upload(MinecraftEntityMesh source);

    @Override default void close() { }

    /** Producer claim on upload buffers and instance data, kept until replacement or removal. */
    interface UploadedEntity extends AutoCloseable {
        MeshBuild<MinecraftProgramTypes.InstanceData> build();
        ShaderData<MinecraftProgramTypes.InstanceData> instanceData();
        @Override void close();
    }
}
