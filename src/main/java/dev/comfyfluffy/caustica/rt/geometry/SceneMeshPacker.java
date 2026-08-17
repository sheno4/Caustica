package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;

import java.util.Arrays;

/** Converts source-neutral meshes into the validated arrays and aligned layout consumed by the GPU scene. */
final class SceneMeshPacker {
    private SceneMeshPacker() {
    }

    static PackedInput pack(SceneMesh mesh, RtGeometryMaterialResolver materialResolver) {
        RtGeometryMeshPacking.PackedMesh packed = RtGeometryMeshPacking.pack(mesh, materialResolver);
        return new PackedInput(packed.positions(), packed.indices(), packed.textureCoordinates(), packed.primitives(),
                packed.classTriangles(), packed.flags());
    }

    static boolean topologyMatches(SceneMesh first, SceneMesh second,
                                   RtGeometryMaterialResolver materialResolver) {
        return Topology.of(pack(first, materialResolver)).matches(Topology.of(pack(second, materialResolver)));
    }

    /** Renderer-private packed representation of a source-neutral scene mesh. */
    static record PackedInput(float[] positions, int[] indices, float[] textureCoordinates, float[] primitives,
                              int[] classTriangles, int semanticFlags) {
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

    static final class Topology {
        private final int vertexCount;
        private final int[] indices;
        private final int[] classTriangles;
        private final int semanticFlags;

        private Topology(int vertexCount, int[] indices, int[] classTriangles, int semanticFlags) {
            this.vertexCount = vertexCount;
            this.indices = indices.clone();
            this.classTriangles = classTriangles.clone();
            this.semanticFlags = semanticFlags;
        }

        static Topology of(PackedInput input) {
            return new Topology(input.positions.length / 3, input.indices, input.classTriangles, input.semanticFlags);
        }

        boolean matches(Topology other) {
            return vertexCount == other.vertexCount && semanticFlags == other.semanticFlags
                    && Arrays.equals(indices, other.indices)
                    && Arrays.equals(classTriangles, other.classTriangles);
        }
    }

    record PackedLayout(long positionOffset, long indexOffset, long textureCoordinateOffset,
                        long primitiveOffset, long totalBytes) {
        static PackedLayout create(int positionFloats, int indexInts, int textureCoordinateFloats,
                                   int primitiveFloats) {
            long positionBytes = (long) positionFloats * Float.BYTES;
            long indexOffset = align(positionBytes);
            long textureOffset = align(indexOffset + (long) indexInts * Integer.BYTES);
            long primitiveOffset = align(textureOffset + (long) textureCoordinateFloats * Float.BYTES);
            return new PackedLayout(0L, indexOffset, textureOffset, primitiveOffset,
                    align(primitiveOffset + (long) primitiveFloats * Float.BYTES));
        }

        PackedLayout shifted(long base) {
            return new PackedLayout(positionOffset + base, indexOffset + base, textureCoordinateOffset + base,
                    primitiveOffset + base, totalBytes + base);
        }

        private static long align(long value) {
            return (value + 15L) & -16L;
        }
    }
}
