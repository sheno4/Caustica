package dev.comfyfluffy.caustica.minecraft.entity;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.minecraft.program.MinecraftPrograms;
import dev.comfyfluffy.caustica.minecraft.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftProgramResources;

/** GPU upload seam for captured entity streams, primitive material records, and texture descriptors. */
public interface MinecraftEntityUploader extends AutoCloseable {
    UploadedEntity upload(MinecraftEntityMesh source);

    @Override default void close() { }

    @FunctionalInterface
    interface Factory {
        MinecraftEntityUploader open(GpuDevice gpu, MinecraftProgramResources resources,
                                     MinecraftPrograms programs);
    }

    /** Resources borrowed by one retained mesh and its placement until the introducing batch retires. */
    interface UploadedEntity extends AutoCloseable {
        MeshBuild<MinecraftProgramTypes.InstanceData> build();
        ShaderData<MinecraftProgramTypes.InstanceData> instanceData();
        @Override void close();
    }
}
