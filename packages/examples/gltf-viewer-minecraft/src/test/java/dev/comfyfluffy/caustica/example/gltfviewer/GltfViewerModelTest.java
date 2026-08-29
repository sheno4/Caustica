package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.example.gltfcontent.GltfLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GltfViewerModelTest {
    @Test
    void bundledViewerModelRetainsItsHierarchyMaterialsAndImages() throws Exception {
        Path source = Path.of(getClass().getResource(
                "/assets/caustica_gltf_viewer/gltf/viewer/model.glb").toURI());
        GltfLoader.Asset asset = GltfLoader.load(source);

        assertEquals(4, asset.nodes().size());
        assertEquals(3, asset.meshes().size());
        assertEquals(1, asset.materials().size());
        assertEquals(4, asset.textures().size());
        assertEquals(4, asset.images().size());
        assertArrayEquals(new int[]{3}, asset.scene().roots());
        assertArrayEquals(new int[]{0, 1, 2}, asset.nodes().get(3).children());
        assertTrue(asset.meshes().stream().allMatch(mesh -> mesh.primitives().size() == 1));
        assertTrue(asset.images().stream().allMatch(
                image -> image.mimeType().equals("image/png") && image.encoded().length > 0));

        GltfLoader.Material material = asset.materials().getFirst();
        assertEquals(0, material.baseColorTexture().texture());
        assertEquals(1, material.metallicRoughnessTexture().texture());
        assertEquals(2, material.normalTexture().texture());
        assertEquals(3, material.emissiveTexture().texture());
    }
}
