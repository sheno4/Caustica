package dev.comfyfluffy.caustica.minecraft.terrain;

import dev.comfyfluffy.caustica.minecraft.rendering.terrain.MinecraftTerrainMesh;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtTerrainMesherPackingTest {
    @Test
    void packsTrianglesIntoStableVulkanRoutingBuckets() {
        var material = route(MinecraftTerrainMesh.ProgramCategory.MATERIAL,
                MinecraftTerrainMesh.Coverage.OPAQUE, 0.5f, null);
        var water = route(MinecraftTerrainMesh.ProgramCategory.WATER,
                MinecraftTerrainMesh.Coverage.OPAQUE, 0.5f, null);
        var micromap = new MinecraftTerrainMesh.OpacityMicromap(0.25f, 0.75f, 2);
        var cutout = route(MinecraftTerrainMesh.ProgramCategory.MATERIAL,
                MinecraftTerrainMesh.Coverage.CUTOUT, 0.5f, micromap);

        int[] indices = integerTriangleLanes(4, 3);
        float[] cornerUvs = triangleLanes(4, 6);
        float[] primitiveData = triangleLanes(4, MinecraftTerrainMesh.PRIMITIVE_FLOATS);

        var packed = RtTerrainMesher.bucketTriangles(indices, cornerUvs, primitiveData,
                List.of(material, water, material, cutout));

        assertArrayEquals(new int[]{0, 1, 2, 6, 7, 8, 3, 4, 5, 9, 10, 11}, packed.indices());
        assertTriangleOrder(new int[]{0, 2, 1, 3}, packed.cornerUvs(), 6);
        assertTriangleOrder(new int[]{0, 2, 1, 3}, packed.primitiveData(),
                MinecraftTerrainMesh.PRIMITIVE_FLOATS);
        assertEquals(8f, packed.primitiveData()[8]);
        assertEquals(208f, packed.primitiveData()[MinecraftTerrainMesh.PRIMITIVE_FLOATS + 8]);
        assertEquals(List.of(
                new MinecraftTerrainMesh.Geometry(MinecraftTerrainMesh.ProgramCategory.MATERIAL,
                        MinecraftTerrainMesh.Coverage.OPAQUE, 0, 6, 0.5f, null),
                new MinecraftTerrainMesh.Geometry(MinecraftTerrainMesh.ProgramCategory.WATER,
                        MinecraftTerrainMesh.Coverage.OPAQUE, 6, 3, 0.5f, null),
                new MinecraftTerrainMesh.Geometry(MinecraftTerrainMesh.ProgramCategory.MATERIAL,
                        MinecraftTerrainMesh.Coverage.CUTOUT, 9, 3, 0.5f, micromap)),
                packed.geometries());
    }

    @Test
    void keepsDistinctCutoffAndMicromapRoutesSeparate() {
        var firstMicromap = new MinecraftTerrainMesh.OpacityMicromap(0.1f, 0.9f, 2);
        var secondMicromap = new MinecraftTerrainMesh.OpacityMicromap(0.2f, 0.8f, 2);
        var packed = RtTerrainMesher.bucketTriangles(integerTriangleLanes(3, 3), triangleLanes(3, 6),
                triangleLanes(3, MinecraftTerrainMesh.PRIMITIVE_FLOATS), List.of(
                        route(MinecraftTerrainMesh.ProgramCategory.MATERIAL,
                                MinecraftTerrainMesh.Coverage.CUTOUT, 0.4f, firstMicromap),
                        route(MinecraftTerrainMesh.ProgramCategory.MATERIAL,
                                MinecraftTerrainMesh.Coverage.CUTOUT, 0.6f, firstMicromap),
                        route(MinecraftTerrainMesh.ProgramCategory.MATERIAL,
                                MinecraftTerrainMesh.Coverage.CUTOUT, 0.4f, secondMicromap)));

        assertEquals(3, packed.geometries().size());
        assertEquals(0.4f, packed.geometries().get(0).alphaCutoff());
        assertEquals(0.6f, packed.geometries().get(1).alphaCutoff());
        assertEquals(secondMicromap, packed.geometries().get(2).opacityMicromap());
    }

    private static RtTerrainMesher.TriangleRouting route(MinecraftTerrainMesh.ProgramCategory program,
                                                          MinecraftTerrainMesh.Coverage coverage,
                                                          float alphaCutoff,
                                                          MinecraftTerrainMesh.OpacityMicromap micromap) {
        return new RtTerrainMesher.TriangleRouting(program, coverage, alphaCutoff, micromap);
    }

    private static int[] integerTriangleLanes(int triangles, int lanes) {
        int[] values = new int[triangles * lanes];
        for (int triangle = 0; triangle < triangles; triangle++) {
            for (int lane = 0; lane < lanes; lane++) values[triangle * lanes + lane] = triangle * lanes + lane;
        }
        return values;
    }

    private static float[] triangleLanes(int triangles, int lanes) {
        float[] values = new float[triangles * lanes];
        for (int triangle = 0; triangle < triangles; triangle++) {
            for (int lane = 0; lane < lanes; lane++) values[triangle * lanes + lane] = triangle * 100f + lane;
        }
        return values;
    }

    private static void assertTriangleOrder(int[] order, float[] actual, int lanes) {
        float[] expected = new float[actual.length];
        for (int destination = 0; destination < order.length; destination++) {
            int source = order[destination];
            for (int lane = 0; lane < lanes; lane++) {
                expected[destination * lanes + lane] = source * 100f + lane;
            }
        }
        assertArrayEquals(expected, actual);
    }
}
