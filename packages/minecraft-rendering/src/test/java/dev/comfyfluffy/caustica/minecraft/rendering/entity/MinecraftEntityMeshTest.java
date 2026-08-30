package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftEntityMeshTest {
    @Test
    void ownsItsGeometryStreamsAndTriangleShadingData() {
        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        var material = new MinecraftEntityMesh.Material(ResourceId.of("minecraft", "stone"), null,
                MinecraftEntityMesh.Program.MATERIAL);
        var triangle = new MinecraftEntityMesh.Triangle(material, MinecraftEntityMesh.Coverage.OPAQUE,
                0, 1, 0, 0);
        float[] colors = {1, 0, 0, .25f, 0, 1, 0, .5f, 0, 0, 1, .75f};

        var mesh = new MinecraftEntityMesh(positions, new int[]{0, 1, 2},
                new float[]{0, 0, 1, 0, 0, 1}, colors, List.of(triangle), 7L);
        positions[0] = 9;

        assertEquals(0, mesh.positions()[0]);
        assertEquals(1, mesh.triangleCount());
        assertEquals(7L, mesh.indexRevision());
        assertEquals(.75f, mesh.vertexColors()[11]);
        assertThrows(IllegalArgumentException.class, () -> new MinecraftEntityMesh(
                new float[]{0, 0, 0}, new int[]{0, 1, 2}, new float[]{0, 0},
                new float[]{1, 1, 1, 1}, List.of(triangle), 0));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftEntityMesh(
                new float[]{0, 0, 0}, new int[]{0}, new float[]{0, 0}, new float[]{1, 1, 1},
                List.of(triangle), 0));
    }
}
