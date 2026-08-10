package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.TriangleMesh;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtGeometryMeshPackingTest {
    @Test
    void packsMaterialRangesInCanonicalSbtOrder() {
        MaterialHandle masked = MaterialHandle.of("test", "masked");
        MaterialHandle opaque = MaterialHandle.of("test", "opaque");
        TriangleMesh mesh = new TriangleMesh(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0},
                new float[8], new int[]{0, 1, 2, 1, 3, 2},
                List.of(new TriangleMesh.MaterialRange(0, 1, masked),
                        new TriangleMesh.MaterialRange(1, 1, opaque)));

        RtGeometryMeshPacking.PackedMesh packed = RtGeometryMeshPacking.pack(mesh, handle ->
                handle.equals(opaque)
                        ? new RtGeometryMaterialResolver.ResolvedMaterial(7, 0)
                        : new RtGeometryMaterialResolver.ResolvedMaterial(9, 1));

        assertArrayEquals(new int[]{1, 3, 2, 0, 1, 2}, packed.indices());
        assertArrayEquals(new int[]{1, 1, 0}, packed.classTris());
        assertEquals(7, Float.floatToRawIntBits(packed.primitives()[8]));
        assertEquals(9, Float.floatToRawIntBits(packed.primitives()[20]));
        assertEquals(1f, packed.primitives()[2]);
    }
}
