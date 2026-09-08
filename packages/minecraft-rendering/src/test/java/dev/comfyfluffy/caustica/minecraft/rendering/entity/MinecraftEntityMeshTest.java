package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftEntityMeshTest {
    @Test
    void copiesOnlyLogicalStreamRangesAndOwnsTheirContents() {
        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0, 99, 99, 99};
        int[] indices = {0, 1, 2, 99, 99, 99};
        float[] uvs = {0, 0, 1, 0, 0, 1, 99, 99};
        float[] colors = {1, 0, 0, .25f, 0, 1, 0, .5f, 0, 0, 1, .75f, 99, 99, 99, 99};
        var material = new MinecraftEntityMesh.Material(ResourceId.of("minecraft", "stone"), null,
                MinecraftEntityMesh.Program.MATERIAL);
        var triangle = new MinecraftEntityMesh.Triangle(material, MinecraftEntityMesh.Coverage.OPAQUE,
                0);
        var triangles = new ArrayList<>(List.of(triangle));

        var mesh = MinecraftEntityMesh.copyOfRanges(positions, indices, uvs, colors, 3, 3, triangles, 17L);
        positions[0] = 9;
        indices[0] = 2;
        uvs[0] = 9;
        colors[0] = 9;
        triangles.clear();
        assertThrows(java.nio.ReadOnlyBufferException.class, () -> mesh.positions().put(0, 8));
        assertThrows(java.nio.ReadOnlyBufferException.class, () -> mesh.indices().put(0, 1));
        assertThrows(java.nio.ReadOnlyBufferException.class, () -> mesh.uvs().put(0, 8));
        assertThrows(java.nio.ReadOnlyBufferException.class, () -> mesh.vertexColors().put(0, 8));

        assertArrayEquals(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, values(mesh.positions()));
        assertArrayEquals(new int[]{0, 1, 2}, values(mesh.indices()));
        assertArrayEquals(new float[]{0, 0, 1, 0, 0, 1}, values(mesh.uvs()));
        assertArrayEquals(new float[]{1, 0, 0, .25f, 0, 1, 0, .5f, 0, 0, 1, .75f}, values(mesh.vertexColors()));
        var reader = mesh.positions();
        reader.position(3);
        assertEquals(0, mesh.positions().position());
        assertThrows(java.nio.ReadOnlyBufferException.class, reader::array);
        assertEquals(3, mesh.vertexCount());
        assertEquals(1, mesh.triangleCount());
        assertEquals(17L, mesh.indexRevision());
        assertEquals(List.of(triangle), mesh.triangles());
        assertThrows(UnsupportedOperationException.class, () -> mesh.triangles().clear());
    }

    @Test
    void ownsItsGeometryStreamsAndTriangleShadingData() {
        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        var material = new MinecraftEntityMesh.Material(ResourceId.of("minecraft", "stone"), null,
                MinecraftEntityMesh.Program.MATERIAL);
        var triangle = new MinecraftEntityMesh.Triangle(material, MinecraftEntityMesh.Coverage.OPAQUE,
                0);
        float[] colors = {1, 0, 0, .25f, 0, 1, 0, .5f, 0, 0, 1, .75f};

        var mesh = new MinecraftEntityMesh(positions, new int[]{0, 1, 2},
                new float[]{0, 0, 1, 0, 0, 1}, colors, List.of(triangle), 7L);
        positions[0] = 9;

        assertEquals(0, mesh.positions().get(0));
        assertEquals(1, mesh.triangleCount());
        assertEquals(7L, mesh.indexRevision());
        assertEquals(.75f, mesh.vertexColors().get(11));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftEntityMesh(
                new float[]{0, 0, 0}, new int[]{0, 1, 2}, new float[]{0, 0},
                new float[]{1, 1, 1, 1}, List.of(triangle), 0));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftEntityMesh(
                new float[]{0, 0, 0}, new int[]{0}, new float[]{0, 0}, new float[]{1, 1, 1},
                List.of(triangle), 0));
    }
    private static float[] values(java.nio.FloatBuffer buffer) {
        float[] result = new float[buffer.remaining()]; buffer.get(result); return result;
    }
    private static int[] values(java.nio.IntBuffer buffer) {
        int[] result = new int[buffer.remaining()]; buffer.get(result); return result;
    }
}
