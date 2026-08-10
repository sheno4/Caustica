package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.provider.TriangleMesh;

import java.util.Arrays;

/** Converts a public mesh into the renderer's three SBT-class index/primitive streams. */
public final class RtGeometryMeshPacking {
    private static final int PRIMITIVE_FLOATS = 12;

    private RtGeometryMeshPacking() {
    }

    public static PackedMesh pack(TriangleMesh mesh, RtGeometryMaterialResolver resolver) {
        int triangleCount = mesh.triangleCount();
        int[] materialIds = new int[triangleCount];
        int[] classes = new int[triangleCount];
        for (TriangleMesh.MaterialRange range : mesh.materials()) {
            RtGeometryMaterialResolver.ResolvedMaterial material = resolver.resolve(range.material());
            int end = range.firstTriangle() + range.triangleCount();
            Arrays.fill(materialIds, range.firstTriangle(), end, material.bindingId());
            Arrays.fill(classes, range.firstTriangle(), end, material.sbtClass());
        }

        float[] positions = mesh.positions();
        float[] texCoords = mesh.texCoords();
        int[] sourceIndices = mesh.indices();
        int[] packedIndices = new int[sourceIndices.length];
        float[] primitives = new float[triangleCount * PRIMITIVE_FLOATS];
        int[] classTris = new int[3];
        int outputTriangle = 0;
        for (int sbtClass = 0; sbtClass < classTris.length; sbtClass++) {
            for (int triangle = 0; triangle < triangleCount; triangle++) {
                if (classes[triangle] != sbtClass) {
                    continue;
                }
                int source = triangle * 3;
                int output = outputTriangle * 3;
                int i0 = sourceIndices[source];
                int i1 = sourceIndices[source + 1];
                int i2 = sourceIndices[source + 2];
                packedIndices[output] = i0;
                packedIndices[output + 1] = i1;
                packedIndices[output + 2] = i2;
                writePrimitive(primitives, outputTriangle * PRIMITIVE_FLOATS,
                        positions, i0, i1, i2, materialIds[triangle]);
                outputTriangle++;
                classTris[sbtClass]++;
            }
        }
        return new PackedMesh(positions, texCoords, packedIndices, primitives, classTris);
    }

    private static void writePrimitive(float[] output, int offset, float[] positions,
                                       int i0, int i1, int i2, int materialId) {
        int a = i0 * 3;
        int b = i1 * 3;
        int c = i2 * 3;
        float abx = positions[b] - positions[a];
        float aby = positions[b + 1] - positions[a + 1];
        float abz = positions[b + 2] - positions[a + 2];
        float acx = positions[c] - positions[a];
        float acy = positions[c + 1] - positions[a + 1];
        float acz = positions[c + 2] - positions[a + 2];
        float nx = aby * acz - abz * acy;
        float ny = abz * acx - abx * acz;
        float nz = abx * acy - aby * acx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!Float.isFinite(length) || length <= 1.0e-8f) {
            throw new IllegalArgumentException("geometry mesh contains a degenerate triangle");
        }
        output[offset] = nx / length;
        output[offset + 1] = ny / length;
        output[offset + 2] = nz / length;
        output[offset + 3] = 0f;
        output[offset + 4] = 1f;
        output[offset + 5] = 1f;
        output[offset + 6] = 1f;
        output[offset + 7] = 0f;
        output[offset + 8] = Float.intBitsToFloat(materialId);
        output[offset + 9] = 0f;
        output[offset + 10] = 0f;
        output[offset + 11] = 0f;
    }

    public record PackedMesh(float[] positions, float[] texCoords, int[] indices,
                             float[] primitives, int[] classTris) {
        public int vertexCount() {
            return positions.length / 3;
        }

        public int triangleCount() {
            return indices.length / 3;
        }
    }
}
