package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceId;

record GltfProgramExports(SurfaceId<PrimitiveData, InstanceData> material,
                          SurfaceId<PrimitiveData, InstanceData> portal) {
    static final ShaderDataType<ImplementationData> IMPLEMENTATION =
            ShaderDataType.create("glTF implementation data");
    static final ShaderDataType<PrimitiveData> PRIMITIVE = ShaderDataType.create("glTF primitive data");
    static final ShaderDataType<InstanceData> INSTANCE = ShaderDataType.create("glTF instance data");

    interface ImplementationData { }
    interface PrimitiveData { }
    interface InstanceData { }
}
