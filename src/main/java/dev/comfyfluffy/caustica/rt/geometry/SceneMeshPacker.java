package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;

/** Converts source-neutral meshes into the validated arrays and aligned layout consumed by the GPU scene. */
final class SceneMeshPacker {
    private SceneMeshPacker() {
    }

    static PackedInput pack(SceneMesh mesh, RtGeometryMaterialResolver materialResolver) {
        RtGeometryMeshPacking.PackedMesh packed = RtGeometryMeshPacking.pack(mesh, materialResolver);
        return new PackedInput(packed.positions(), packed.indices(), packed.textureCoordinates(),
                packed.vertexNormals(), packed.vertexColors(), packed.primitives(), packed.classTriangles(),
                packed.flags(), mesh.topologyRevision());
    }

    /** Renderer-private packed representation of a source-neutral scene mesh. */
    static record PackedInput(float[] positions, int[] indices, float[] textureCoordinates,
                              float[] vertexNormals, float[] vertexColors, float[] primitives,
                              int[] classTriangles, int semanticFlags,
                              SceneMesh.TopologyRevision topologyRevision) {
        PackedInput {
            if (positions.length == 0 || positions.length % 3 != 0 || indices.length == 0 || indices.length % 3 != 0) {
                throw new IllegalArgumentException("packed geometry must contain complete vertices and triangles");
            }
            if (classTriangles.length != RtAccel.SBT_CLASSES) {
                throw new IllegalArgumentException("packed geometry must provide every acceleration-structure class");
            }
            int vertexCount = positions.length / 3;
            int triangleCount = indices.length / 3;
            int classTriangleCount = 0;
            for (int count : classTriangles) {
                if (count < 0) throw new IllegalArgumentException("packed geometry class count must be non-negative");
                classTriangleCount = Math.addExact(classTriangleCount, count);
            }
            if (classTriangleCount != triangleCount) {
                throw new IllegalArgumentException("packed geometry class counts must cover every triangle");
            }
            int uvFlags = semanticFlags & (RtGeometryAbi.FLAG_INDEXED_TEXTURE_COORDINATES
                    | RtGeometryAbi.FLAG_TRIANGLE_CORNER_TEXTURE_COORDINATES);
            if (uvFlags != RtGeometryAbi.FLAG_INDEXED_TEXTURE_COORDINATES
                    && uvFlags != RtGeometryAbi.FLAG_TRIANGLE_CORNER_TEXTURE_COORDINATES) {
                throw new IllegalArgumentException("packed geometry must declare exactly one texture-coordinate layout");
            }
            int expectedTextureCoordinates = uvFlags == RtGeometryAbi.FLAG_INDEXED_TEXTURE_COORDINATES
                    ? vertexCount * 2 : triangleCount * 6;
            if (textureCoordinates.length != expectedTextureCoordinates) {
                throw new IllegalArgumentException(
                        "packed geometry texture coordinates do not match their declared layout");
            }
            if (vertexNormals.length != 0 && vertexNormals.length != vertexCount * 3) {
                throw new IllegalArgumentException("packed geometry vertex normals do not match its vertices");
            }
            if (vertexColors.length != 0 && vertexColors.length != vertexCount * 4) {
                throw new IllegalArgumentException("packed geometry vertex colors do not match its vertices");
            }
            if ((vertexNormals.length != 0) != ((semanticFlags & RtGeometryAbi.FLAG_HAS_VERTEX_NORMALS) != 0)) {
                throw new IllegalArgumentException("packed geometry vertex-normal flag does not match its data");
            }
            if ((vertexColors.length != 0) != ((semanticFlags & RtGeometryAbi.FLAG_HAS_VERTEX_COLORS) != 0)) {
                throw new IllegalArgumentException("packed geometry vertex-color flag does not match its data");
            }
            if (primitives.length != triangleCount * 12) {
                throw new IllegalArgumentException("packed geometry must provide one primitive record per triangle");
            }
            for (int index : indices) {
                if (index < 0 || index >= vertexCount) {
                    throw new IllegalArgumentException("packed geometry index is outside its vertex range");
                }
            }
        }
    }

    static record Topology(SceneMesh.TopologyRevision revision, int vertexCount, int indexCount,
                           int opaqueTriangleCount, int maskedTriangleCount, int transmissiveTriangleCount,
                           int semanticFlags) {
        static Topology of(PackedInput input) {
            return new Topology(input.topologyRevision, input.positions.length / 3, input.indices.length,
                    input.classTriangles[RtAccel.CLASS_OPAQUE], input.classTriangles[RtAccel.CLASS_MASKED],
                    input.classTriangles[RtAccel.CLASS_TRANSMISSIVE], input.semanticFlags);
        }
    }

    record PackedLayout(long positionOffset, long indexOffset, long textureCoordinateOffset,
                        long vertexNormalOffset, long vertexColorOffset, long primitiveOffset, long totalBytes) {
        static PackedLayout create(int positionFloats, int indexInts, int textureCoordinateFloats,
                                   int vertexNormalFloats, int vertexColorFloats, int primitiveFloats) {
            long positionBytes = (long) positionFloats * Float.BYTES;
            long indexOffset = align(positionBytes);
            long textureOffset = align(indexOffset + (long) indexInts * Integer.BYTES);
            long normalOffset = align(textureOffset + (long) textureCoordinateFloats * Float.BYTES);
            long colorOffset = align(normalOffset + (long) vertexNormalFloats * Float.BYTES);
            long primitiveOffset = align(colorOffset + (long) vertexColorFloats * Float.BYTES);
            return new PackedLayout(0L, indexOffset, textureOffset, normalOffset, colorOffset, primitiveOffset,
                    align(primitiveOffset + (long) primitiveFloats * Float.BYTES));
        }

        PackedLayout shifted(long base) {
            return new PackedLayout(positionOffset + base, indexOffset + base, textureCoordinateOffset + base,
                    vertexNormalOffset + base, vertexColorOffset + base, primitiveOffset + base, totalBytes + base);
        }

        private static long align(long value) {
            return (value + 15L) & -16L;
        }
    }
}
