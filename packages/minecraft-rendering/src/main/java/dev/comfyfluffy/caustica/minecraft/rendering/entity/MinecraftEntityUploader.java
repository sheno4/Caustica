package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;

/** GPU upload seam for captured entity streams, primitive material records, and texture descriptors. */
public interface MinecraftEntityUploader extends AutoCloseable {
    UploadedEntity upload(MinecraftEntityMesh source);

    /** Captures host texture claims before a worker performs the upload's CPU packing. */
    default UploadJob prepareUpload(MinecraftEntityMesh source) {
        return new UploadJob() {
            private UploadedEntity uploaded = upload(source);
            @Override public UploadedEntity finish() {
                var result = uploaded;
                uploaded = null;
                return result;
            }
            @Override public void close() {
                var released = uploaded;
                uploaded = null;
                if (released != null) released.close();
            }
        };
    }

    /** Owns upload inputs until finish transfers them to the returned uploaded revision. */
    interface UploadJob extends AutoCloseable {
        UploadedEntity finish();
        @Override void close();
    }

    @Override default void close() { }

    /** Producer claim on upload buffers and instance data, kept until replacement or removal. */
    interface UploadedEntity extends AutoCloseable {
        MeshBuild<MinecraftProgramTypes.InstanceData> build();
        ShaderData<MinecraftProgramTypes.InstanceData> instanceData();
        @Override void close();
    }
}
