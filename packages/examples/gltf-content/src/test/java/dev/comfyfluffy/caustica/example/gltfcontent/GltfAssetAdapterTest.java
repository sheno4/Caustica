package dev.comfyfluffy.caustica.example.gltfcontent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class GltfAssetAdapterTest {
    @Test
    void retainsEachPrimitiveOnceAndPreservesAuthoredNodeInstances() {
        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        GltfLoader.Primitive primitive = new GltfLoader.Primitive(
                positions, new int[]{0, 1, 2}, new float[0], new float[0], new float[0], new float[0], 0);
        GltfLoader.Material material = new GltfLoader.Material("cutout", .25f, .5f, .75f, .4f,
                null, .2f, .3f, null, null, 0, 0, 0, null,
                GltfLoader.AlphaMode.MASK, .35f, false, 1.5f, 0, 1);
        GltfLoader.Asset asset = new GltfLoader.Asset(new GltfLoader.Scene("scene", new int[]{0}),
                List.of(new GltfLoader.Node("root", 0, translation(1, 2, 3), new int[]{1}),
                        new GltfLoader.Node("child", 0, translation(4, 5, 6), new int[0])),
                List.of(new GltfLoader.Mesh("shared", List.of(primitive))),
                List.of(material), List.of(), List.of());

        GltfScene scene = GltfAssetAdapter.adapt(asset);

        assertEquals(1, scene.primitives().size());
        assertEquals(2, scene.placements().size());
        assertArrayEquals(positions, scene.primitives().getFirst().positions());
        assertEquals(.35f, scene.primitives().getFirst().alphaCutoff());
        assertEquals(11.0, scene.placements().getFirst().at(10, 20, 30).translationX());
        assertEquals(22.0, scene.placements().getFirst().at(10, 20, 30).translationY());
        assertEquals(33.0, scene.placements().getFirst().at(10, 20, 30).translationZ());
        assertArrayEquals(translation(5, 7, 9), scene.placements().getLast().nodeWorldMatrix());
    }

    private static float[] translation(float x, float y, float z) {
        return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1};
    }
}
