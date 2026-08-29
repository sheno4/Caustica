package dev.comfyfluffy.caustica.example.gltfcontent;

import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceId;

public record GltfProgramExports(SurfaceId<PrimitiveData, InstanceData> material,
                                 SurfaceId<PrimitiveData, InstanceData> portal) {
    public static final ShaderDataType<ImplementationData> IMPLEMENTATION =
            ShaderDataType.create("glTF implementation data");
    public static final ShaderDataType<PrimitiveData> PRIMITIVE = ShaderDataType.create("glTF primitive data");
    public static final ShaderDataType<InstanceData> INSTANCE = ShaderDataType.create("glTF instance data");

    public interface ImplementationData { }
    public interface PrimitiveData { }
    public interface InstanceData { }
}
