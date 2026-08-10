package dev.comfyfluffy.caustica.minecraft.cloud;

import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.TriangleMesh;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RoundedCloudMeshTest {
    @Test
    void generatedCloudIsDeterministicAndActuallyRounded() {
        MaterialHandle material = MaterialHandle.of("test", "cloud");
        TriangleMesh first = RoundedCloudMesh.generate(42L, material);
        TriangleMesh second = RoundedCloudMesh.generate(42L, material);

        assertEquals(first, second);
        assertEquals(6 * 18, first.triangleCount());
        float[] positions = first.positions();
        int[] indices = first.indices();
        boolean foundRoundedNormal = false;
        for (int triangle = 0; triangle < first.triangleCount(); triangle++) {
            int a = indices[triangle * 3] * 3;
            int b = indices[triangle * 3 + 1] * 3;
            int c = indices[triangle * 3 + 2] * 3;
            float abx = positions[b] - positions[a];
            float aby = positions[b + 1] - positions[a + 1];
            float abz = positions[b + 2] - positions[a + 2];
            float acx = positions[c] - positions[a];
            float acy = positions[c + 1] - positions[a + 1];
            float acz = positions[c + 2] - positions[a + 2];
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            float areaSquared = nx * nx + ny * ny + nz * nz;
            assertTrue(Float.isFinite(areaSquared) && areaSquared > 1.0e-8f,
                    "every generated triangle must be finite and non-degenerate");
            float centerX = (positions[a] + positions[b] + positions[c]) / 3f;
            float centerY = (positions[a + 1] + positions[b + 1] + positions[c + 1]) / 3f;
            float centerZ = (positions[a + 2] + positions[b + 2] + positions[c + 2]) / 3f;
            assertTrue(nx * centerX + ny * centerY + nz * centerZ > 0f,
                    "the rounded box must use outward winding");
            float inverseLength = 1f / (float) Math.sqrt(areaSquared);
            float anx = Math.abs(nx * inverseLength);
            float any = Math.abs(ny * inverseLength);
            float anz = Math.abs(nz * inverseLength);
            foundRoundedNormal |= anx > 0.05f && any > 0.05f && anz > 0.05f;
        }
        assertTrue(foundRoundedNormal, "corner tessellation must contain genuinely curved normals");
    }
}
