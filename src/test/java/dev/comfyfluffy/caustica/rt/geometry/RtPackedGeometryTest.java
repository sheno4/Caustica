package dev.comfyfluffy.caustica.rt.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtPackedGeometryTest {
    @Test
    void retainsPackedArraysWithoutCopying() {
        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        int[] indices = {0, 1, 2};
        float[] textureCoordinates = {0, 0, 1, 0, 0, 1};
        float[] primitives = new float[RtPackedGeometry.PRIMITIVE_FLOATS];
        int[] classTriangles = {1, 0, 0};
        int[] triangleBases = {0, 1, 1};
        Object metadata = new Object();

        RtPackedGeometry<Object> packed = new RtPackedGeometry<>(positions, indices,
                textureCoordinates, primitives, classTriangles, triangleBases, metadata);

        assertSame(positions, packed.positions());
        assertSame(indices, packed.indices());
        assertSame(textureCoordinates, packed.textureCoordinates());
        assertSame(primitives, packed.primitives());
        assertSame(metadata, packed.metadata());
        assertEquals(3, packed.vertexCount());
        assertEquals(1, packed.triangleCount());
    }

    @Test
    void rejectsMismatchedPrimitiveAndClassCardinality() {
        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        int[] indices = {0, 1, 2};
        float[] textureCoordinates = new float[6];

        assertThrows(IllegalArgumentException.class, () -> new RtPackedGeometry<>(positions, indices,
                textureCoordinates, new float[11], new int[]{1, 0, 0}, new int[]{0, 1, 1}, null));
        assertThrows(IllegalArgumentException.class, () -> new RtPackedGeometry<>(positions, indices,
                textureCoordinates, new float[12], new int[]{0, 1, 0}, new int[]{0, 1, 1}, null));
    }
}
