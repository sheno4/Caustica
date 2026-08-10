package dev.comfyfluffy.caustica.api.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TriangleMeshTest {
    private static final MaterialHandle MATERIAL = MaterialHandle.of("test", "solid");

    @Test
    void ownsItsArraysAndRequiresCompleteMaterialCoverage() {
        float[] positions = {0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f};
        TriangleMesh mesh = new TriangleMesh(positions, new float[6], new int[]{0, 1, 2},
                List.of(new TriangleMesh.MaterialRange(0, 1, MATERIAL)));
        positions[0] = 9f;
        assertEquals(0f, mesh.positions()[0]);

        assertThrows(IllegalArgumentException.class, () -> new TriangleMesh(
                positions, new float[6], new int[]{0, 1, 2}, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TriangleMesh(
                new float[]{0, 0, 0, Float.NaN, 0, 0, 0, 1, 0}, new float[6], new int[]{0, 1, 2},
                List.of(new TriangleMesh.MaterialRange(0, 1, MATERIAL))));
    }

    @Test
    void keepsWorldTranslationPreciseUntilRebase() {
        GeometryTransform transform = GeometryTransform.translation(30_000_000.25, 128.0, -30_000_000.5);
        float[] relative = transform.relativeTo(30_000_000, 120, -30_000_000);
        assertEquals(0.25f, relative[3]);
        assertEquals(8f, relative[7]);
        assertEquals(-0.5f, relative[11]);
    }
}
