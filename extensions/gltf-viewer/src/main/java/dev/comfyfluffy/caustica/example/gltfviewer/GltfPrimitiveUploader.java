package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;

interface GltfPrimitiveUploader {
    Uploaded upload(GltfViewerScene.Primitive primitive);

    interface Uploaded {
        MeshBuild.Stream positionsStream();
        MeshBuild.Stream indexStream();
        long primitiveDataAddress();
        int vertexCount();
        int indexCount();
        void destroy();
    }
}
