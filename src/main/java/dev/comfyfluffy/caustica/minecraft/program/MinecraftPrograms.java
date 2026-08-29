package dev.comfyfluffy.caustica.minecraft.program;

import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeId;

/** IDs published together by the atomic Minecraft world program registration. */
public record MinecraftPrograms(
        SurfaceId<MinecraftProgramTypes.PrimitiveData, MinecraftProgramTypes.InstanceData> materialSurface,
        SurfaceId<MinecraftProgramTypes.PrimitiveData, MinecraftProgramTypes.InstanceData> waterSurface,
        SurfaceId<MinecraftProgramTypes.PrimitiveData, MinecraftProgramTypes.InstanceData> portalSurface,
        VolumeId<MinecraftProgramTypes.PrimitiveData, MinecraftProgramTypes.InstanceData> waterVolume,
        EnvironmentId<MinecraftProgramTypes.EnvironmentBindingData> environment) { }
