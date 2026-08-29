package dev.comfyfluffy.caustica.example.gltfcontent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Adapts supported triangle primitives without altering authored positions or node transforms. */
public final class GltfAssetAdapter {
    private GltfAssetAdapter() { }

    public static GltfScene adapt(GltfLoader.Asset asset) {
        List<GltfScene.Primitive> primitives = new ArrayList<>();
        int[][] primitiveIds = new int[asset.meshes().size()][];
        for (int meshIndex = 0; meshIndex < asset.meshes().size(); meshIndex++) {
            GltfLoader.Mesh mesh = asset.meshes().get(meshIndex);
            primitiveIds[meshIndex] = new int[mesh.primitives().size()];
            for (int primitiveIndex = 0; primitiveIndex < mesh.primitives().size(); primitiveIndex++) {
                GltfLoader.Primitive source = mesh.primitives().get(primitiveIndex);
                GltfLoader.Material material = source.material() < 0
                        ? defaultMaterial() : asset.materials().get(source.material());
                int[] indices = nonDegenerateIndices(source.positions(), source.indices());
                boolean cutout = material.alphaMode() == GltfLoader.AlphaMode.MASK;
                primitiveIds[meshIndex][primitiveIndex] = primitives.size();
                primitives.add(new GltfScene.Primitive(source.positions(), indices,
                        material.baseColorR(), material.baseColorG(), material.baseColorB(), material.baseColorA(),
                        material.roughness(), material.metallic(), cutout, material.alphaCutoff()));
            }
        }

        List<GltfScene.Placement> placements = new ArrayList<>();
        for (int root : asset.scene().roots()) collect(asset, root, identity(), primitiveIds, placements);
        return new GltfScene(primitives, placements);
    }

    private static void collect(GltfLoader.Asset asset, int nodeIndex, float[] parent,
                                int[][] primitiveIds, List<GltfScene.Placement> placements) {
        GltfLoader.Node node = asset.nodes().get(nodeIndex);
        float[] world = multiply(parent, node.localMatrix());
        if (node.mesh() >= 0) {
            for (int primitive : primitiveIds[node.mesh()]) {
                placements.add(new GltfScene.Placement(primitive, world));
            }
        }
        for (int child : node.children()) collect(asset, child, world, primitiveIds, placements);
    }

    public static int[] nonDegenerateIndices(float[] positions, int[] indices) {
        int[] result = new int[indices.length];
        int count = 0;
        for (int offset = 0; offset < indices.length; offset += 3) {
            int a = indices[offset] * 3;
            int b = indices[offset + 1] * 3;
            int c = indices[offset + 2] * 3;
            float abx = positions[b] - positions[a], aby = positions[b + 1] - positions[a + 1];
            float abz = positions[b + 2] - positions[a + 2];
            float acx = positions[c] - positions[a], acy = positions[c + 1] - positions[a + 1];
            float acz = positions[c + 2] - positions[a + 2];
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            if (nx * nx + ny * ny + nz * nz > 1.0e-16f) {
                result[count++] = indices[offset];
                result[count++] = indices[offset + 1];
                result[count++] = indices[offset + 2];
            }
        }
        if (count == 0) throw new IllegalArgumentException("glTF primitive has no non-degenerate triangles");
        return count == result.length ? result : Arrays.copyOf(result, count);
    }

    private static GltfLoader.Material defaultMaterial() {
        return new GltfLoader.Material("default", 1, 1, 1, 1, null,
                1, 1, null, null, 0, 0, 0, null,
                GltfLoader.AlphaMode.OPAQUE, 0.5f, false, 1.5f, 0, 1);
    }

    private static float[] identity() {
        return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static float[] multiply(float[] left, float[] right) {
        float[] result = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                for (int k = 0; k < 4; k++) {
                    result[column * 4 + row] += left[k * 4 + row] * right[column * 4 + k];
                }
            }
        }
        return result;
    }
}
