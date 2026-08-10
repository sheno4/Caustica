package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.rt.accel.RtAccel;

import java.util.Objects;

/** Non-copying CPU handoff for one retained packed triangle stream and opaque source metadata. */
public record RtPackedGeometry<M>(float[] positions, int[] indices, float[] textureCoordinates,
                                  float[] primitives, int[] classTriangles, int[] triangleBases,
                                  M metadata) {
    public static final int PRIMITIVE_FLOATS = 12;

    public RtPackedGeometry {
        Objects.requireNonNull(positions, "positions");
        Objects.requireNonNull(indices, "indices");
        Objects.requireNonNull(textureCoordinates, "textureCoordinates");
        Objects.requireNonNull(primitives, "primitives");
        Objects.requireNonNull(classTriangles, "classTriangles");
        Objects.requireNonNull(triangleBases, "triangleBases");
        if (positions.length == 0 || positions.length % 3 != 0) {
            throw new IllegalArgumentException("positions must contain complete xyz vertices");
        }
        if (indices.length == 0 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("indices must contain complete triangles");
        }
        if (textureCoordinates.length != indices.length * 2) {
            throw new IllegalArgumentException("texture coordinates must contain one uv pair per triangle corner");
        }
        if (primitives.length != triangleCount(indices) * PRIMITIVE_FLOATS) {
            throw new IllegalArgumentException("primitive records must contain 12 floats per triangle");
        }
        if (classTriangles.length != RtAccel.SBT_CLASSES || triangleBases.length != RtAccel.SBT_CLASSES) {
            throw new IllegalArgumentException("class counts and bases must match the fixed SBT classes");
        }
        int total = 0;
        for (int i = 0; i < classTriangles.length; i++) {
            if (classTriangles[i] < 0 || triangleBases[i] != total) {
                throw new IllegalArgumentException("triangle classes must be contiguous and ordered");
            }
            total = Math.addExact(total, classTriangles[i]);
        }
        if (total != triangleCount(indices)) {
            throw new IllegalArgumentException("triangle classes must cover every triangle");
        }
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public int triangleCount() {
        return triangleCount(indices);
    }

    private static int triangleCount(int[] indices) {
        return indices.length / 3;
    }
}
