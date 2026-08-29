package dev.comfyfluffy.caustica.example.gltfcontent;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;

public interface GltfPrimitiveUploader {
    Uploaded upload(GltfScene.Primitive primitive);

    interface Uploaded {
        MeshBuild.Stream positionsStream();
        MeshBuild.Stream indexStream();
        VulkanDeviceAddress primitiveDataAddress();
        int vertexCount();
        int indexCount();
        void destroy();
    }
}
