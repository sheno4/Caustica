package dev.comfyfluffy.caustica.example.gltfcontent;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;

public interface GltfPrimitiveUploader {
    Uploaded upload(ResourceFactory resources, GltfScene.Primitive primitive);

    interface Uploaded {
        MeshBuild.Stream positionsStream();
        MeshBuild.Stream indexStream();
        VulkanDeviceAddress primitiveDataAddress();
        ResourceOwner primitiveDataResource();
        int vertexCount();
        int indexCount();
        void drop();
    }
}
