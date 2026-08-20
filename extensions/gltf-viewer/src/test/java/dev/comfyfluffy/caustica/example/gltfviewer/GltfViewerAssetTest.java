package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.ColorSpaces;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class GltfViewerAssetTest {
    @Test
    void retainsEachPrimitiveOnceAndPreservesEveryAuthoredNodeInstance() {
        float[] positions = {0.25f, 0.5f, -2.0f, 3.0f, 0.0f, 1.0f, -1.0f, 4.0f, 2.0f};
        float[] colors = {0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1, 1, 1, 1};
        GltfLoader.Primitive primitive = new GltfLoader.Primitive(
                positions, new int[]{0, 1, 2}, new float[0], new float[0],
                colors, new float[0], 0);
        GltfLoader.Material material = new GltfLoader.Material("tinted", 0.25f, 0.5f, 0.75f, 0.5f,
                null, 0.2f, 0.3f, null, null, 0, 0, 0, null,
                GltfLoader.AlphaMode.BLEND, 0.5f, false, 1.5f, 0, 1);
        GltfLoader.Node root = new GltfLoader.Node("root", 0, translation(1, 2, 3), new int[]{1});
        GltfLoader.Node child = new GltfLoader.Node("child", 0, translation(4, 5, 6), new int[0]);
        GltfLoader.Asset asset = new GltfLoader.Asset(
                new GltfLoader.Scene("scene", new int[]{0}), List.of(root, child),
                List.of(new GltfLoader.Mesh("shared", List.of(primitive))),
                List.of(material), List.of(), List.of());

        GltfViewerScene scene = GltfViewerAsset.adapt(asset);

        assertEquals(1, scene.residents().size());
        assertEquals(2, scene.placements().size());
        assertArrayEquals(positions, scene.residents().getFirst().mesh().positions());
        float[] firstColor = ColorSpaces.linearBt709ToAcesCg(0.2f, 0.3f, 0.4f);
        float[] secondColor = ColorSpaces.linearBt709ToAcesCg(0.6f, 0.7f, 0.8f);
        assertArrayEquals(new float[]{
                firstColor[0], firstColor[1], firstColor[2], 0.25f,
                secondColor[0], secondColor[1], secondColor[2], 0.45f,
                1, 1, 1, 0.5f
        }, scene.residents().getFirst().mesh().vertexColors());
        assertEquals(scene.residents().getFirst().key(), scene.placements().get(0).resident());
        assertEquals(scene.residents().getFirst().key(), scene.placements().get(1).resident());
        assertArrayEquals(translation(1, 2, 3), scene.placements().get(0).nodeWorldMatrix());
        assertArrayEquals(translation(5, 7, 9), scene.placements().get(1).nodeWorldMatrix());
        assertEquals(0.0f, scene.materials().getFirst().definition().emissionLuminanceCdM2());

        GltfViewerScene reloaded = GltfViewerAsset.adapt(asset);
        assertNotEquals(scene.residents().getFirst().mesh().topologyRevision(),
                reloaded.residents().getFirst().mesh().topologyRevision());
    }

    @Test
    void appliesViewerPhotometricReferenceToAuthoredEmissiveStrength() {
        GltfLoader.Material material = new GltfLoader.Material("emissive", 1, 1, 1, 1,
                null, 1, 1, null, null, 1, 0.5f, 0.25f, null,
                GltfLoader.AlphaMode.OPAQUE, 0.5f, false, 1.5f, 0, 2);
        GltfLoader.Asset asset = new GltfLoader.Asset(
                new GltfLoader.Scene("scene", new int[0]), List.of(), List.of(),
                List.of(material), List.of(), List.of());

        GltfViewerScene scene = GltfViewerAsset.adapt(asset);

        assertEquals(8000.0f, scene.materials().getFirst().definition().emissionLuminanceCdM2());
    }

    @Test
    void rejectsTextureCoordinateSetsTheRendererCannotRepresent() {
        GltfLoader.Material material = new GltfLoader.Material("material", 1, 1, 1, 1,
                new GltfLoader.TextureInfo(0, 1, 1), 1, 1, null, null,
                0, 0, 0, null, GltfLoader.AlphaMode.OPAQUE, 0.5f,
                false, 1.5f, 0, 1);
        GltfLoader.Asset asset = new GltfLoader.Asset(
                new GltfLoader.Scene("scene", new int[0]), List.of(), List.of(),
                List.of(material), List.of(), List.of());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> GltfViewerAsset.adapt(asset));
        assertEquals("base-color texture uses unsupported TEXCOORD_1", exception.getMessage());
    }

    @Test
    void omitsZeroAreaTrianglesRejectedByTheRendererAbi() {
        GltfLoader.Primitive primitive = new GltfLoader.Primitive(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[]{0, 0, 1, 0, 1, 2}, new float[0], new float[0],
                new float[0], new float[0], -1);
        GltfLoader.Asset asset = new GltfLoader.Asset(
                new GltfLoader.Scene("scene", new int[]{0}),
                List.of(new GltfLoader.Node("node", 0, translation(0, 0, 0), new int[0])),
                List.of(new GltfLoader.Mesh("mesh", List.of(primitive))),
                List.of(), List.of(), List.of());

        GltfViewerScene scene = GltfViewerAsset.adapt(asset);

        assertArrayEquals(new int[]{0, 1, 2}, scene.residents().getFirst().mesh().indices());
    }

    @Test
    void conservativeOmmRangeMultipliesBaseTextureAndTriangleVertexAlpha() {
        var image = new GltfImageData(2, 1,
                new int[]{0x40FFFFFF, 0xC0FFFFFF});

        var range = GltfViewerAsset.triangleOpacityMicromapRange(image, 0.5f, 0.25f, 0.75f);

        assertEquals((0x40 / 255.0f) * 0.25f, range.minCoverage(), 1.0e-6f);
        assertEquals((0xC0 / 255.0f) * 0.75f, range.maxCoverage(), 1.0e-6f);
    }

    private static float[] translation(float x, float y, float z) {
        return new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                x, y, z, 1
        };
    }
}
