package dev.comfyfluffy.caustica.minecraft.program;

import dev.comfyfluffy.caustica.api.program.ShaderDataType;

/** Typed Java tokens for Minecraft shader data words. */
public final class MinecraftProgramTypes {
    public interface ImplementationData { }
    public interface PrimitiveData { }
    public interface InstanceData { }
    public interface EnvironmentBindingData { }

    public static final ShaderDataType<ImplementationData> IMPLEMENTATION_DATA =
            ShaderDataType.create("Minecraft implementation data");
    public static final ShaderDataType<PrimitiveData> PRIMITIVE_DATA =
            ShaderDataType.create("Minecraft primitive data");
    public static final ShaderDataType<InstanceData> INSTANCE_DATA =
            ShaderDataType.create("Minecraft instance data");
    public static final ShaderDataType<EnvironmentBindingData> ENVIRONMENT_BINDING_DATA =
            ShaderDataType.create("Minecraft environment binding data");

    private MinecraftProgramTypes() { }
}
