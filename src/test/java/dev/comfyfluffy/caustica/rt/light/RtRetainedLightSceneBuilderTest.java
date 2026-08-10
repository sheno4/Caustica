package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.engine.light.LightDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtRetainedLightSceneBuilderTest {
    @Test
    void buildsStableBatchRangesAndPowerWeightedLocalAliases() {
        RtRetainedLightSceneBuilder.Data data = RtRetainedLightSceneBuilder.build(List.of(
                batch(5, 4, 2, 1, rectangle(2, 1)),
                batch(2, 1, 0, 0, rectangle(1, 1), rectangle(1, 3))
        ), 16, 0, 0, 1.0, () -> false);

        assertEquals(3, data.lightCount());
        assertEquals(0, data.batchFirstLights()[2]);
        assertEquals(2, data.batchLightCounts()[2]);
        assertEquals(2, data.batchFirstLights()[5]);
        assertEquals(1, data.batchLightCounts()[5]);

        assertEquals(0.25, aliasProbability(data.localAliases(), 0, 2, 0), 1.0e-6);
        assertEquals(0.75, aliasProbability(data.localAliases(), 0, 2, 1), 1.0e-6);
        int stride = RtRetainedLightSceneBuilder.GPU_FLOATS_PER_LIGHT;
        assertEquals(0f, data.packedLights()[0], 0f);
        assertEquals(48f, data.packedLights()[2 * stride], 0f);
        assertPackedCoord(data.packedLights()[stride - 1], 2, 2, 2);
        assertPackedCoord(data.packedLights()[2 * stride + stride - 1], 5, 4, 3);
        assertEquals(1f / 6f, data.invGlobalPowerSum(), 1.0e-6f);
        assertEquals(3L * stride * Float.BYTES, data.lightBytes());
        assertEquals(3L * 8L, data.globalAliases().bytes());
        assertEquals(3, data.lightBvh().lights().size());
        assertEquals(5, data.lightBvh().nodes().size());
        assertEquals(6.0 * Math.PI, data.lightBvh().totalLuminousPowerLumens(), 1.0e-6);
    }

    @Test
    void lightGridAliasSpansEmbedSortedFlatLightRanges() {
        RtRetainedLightSceneBuilder.Data data = RtRetainedLightSceneBuilder.build(List.of(
                batch(9, 0, 0, 0, rectangle(1, 1)),
                batch(3, 1, 0, 0, rectangle(1, 1))
        ), 0, 0, 0, 1.0, () -> false);

        RtRetainedLightGrid.Data grid = data.grid();
        int cellX = -grid.originX() / 16;
        int cellY = -grid.originY() / 16;
        int cellZ = -grid.originZ() / 16;
        int centerCell = (cellZ * grid.dimY() + cellY) * grid.dimX() + cellX;
        int firstSpan = grid.cellOffsets()[centerCell];
        assertEquals(2, grid.cellCounts()[centerCell]);
        assertEquals(0, grid.spanFirstLights()[firstSpan]);
        assertEquals(1, grid.spanFirstLights()[firstSpan + 1]);
        assertEquals(1, grid.spanLightCounts()[firstSpan]);
        assertEquals(1, grid.spanLightCounts()[firstSpan + 1]);
        assertEquals(0.5, spanAliasProbability(grid, firstSpan, 2, 0), 1.0e-6);
        assertEquals(0.5, spanAliasProbability(grid, firstSpan, 2, 1), 1.0e-6);
        assertEquals(grid.spanFirstLights().length * 16L, grid.spanBytes());
    }

    @Test
    void mortonOrderOverridesUnrelatedStableSlotOrder() {
        RtRetainedLightSceneBuilder.Data data = RtRetainedLightSceneBuilder.build(List.of(
                batch(0, 8, 0, 0, rectangle(1, 1)),
                batch(9, 0, 0, 0, rectangle(1, 1))), 0, 0, 0, 1.0, () -> false);

        assertEquals(0, data.batchFirstLights()[9]);
        assertEquals(1, data.batchFirstLights()[0]);
    }

    @Test
    void packedRadianceRoundTripsRepresentativeHdrValues() {
        int packed = RtRetainedLightSceneBuilder.packR11G11B10(0.125f, 5.0f, 31.5f);
        assertEquals(0.125f, RtRetainedLightSceneBuilder.unpackUnsignedFloat(packed & 0x7ff, 6), 0.002f);
        assertEquals(5.0f, RtRetainedLightSceneBuilder.unpackUnsignedFloat((packed >>> 11) & 0x7ff, 6), 0.04f);
        assertEquals(31.5f, RtRetainedLightSceneBuilder.unpackUnsignedFloat((packed >>> 22) & 0x3ff, 5), 0.5f);
    }

    @Test
    void retainedBatchCanBeTranslatedAcrossARebase() {
        List<RetainedLightBatch> batches = List.of(batch(0, 3, 0, 0, rectangle(1, 1)));
        RtRetainedLightSceneBuilder.Data oldGeneration = RtRetainedLightSceneBuilder.build(
                batches, 16, 0, 0, 1.0, () -> false);
        RtRetainedLightSceneBuilder.Data rebuiltGeneration = RtRetainedLightSceneBuilder.build(
                batches, 32, 0, 0, 1.0, () -> false);

        float oldToCurrent = oldGeneration.rebaseX() - rebuiltGeneration.rebaseX();
        assertEquals(rebuiltGeneration.packedLights()[0],
                oldGeneration.packedLights()[0] + oldToCurrent, 0f);
        assertEquals(rebuiltGeneration.grid().originX(),
                oldGeneration.grid().originX() + oldToCurrent, 0f);
    }

    @Test
    void supersededBuildStopsCooperatively() {
        List<RetainedLightBatch> batches = List.of(batch(0, 0, 0, 0, rectangle(1, 1)));

        assertThrows(CancellationException.class,
                () -> RtRetainedLightSceneBuilder.build(batches, 0, 0, 0, 1.0, () -> true));
    }

    private static RetainedLightBatch batch(int slot, int x, int y, int z,
                                            LightDescriptor.Finite... lights) {
        List<LightDescriptor.Finite> absolute = java.util.Arrays.stream(lights)
                .<LightDescriptor.Finite>map(
                        light -> translate(light, x * 16.0, y * 16.0, z * 16.0))
                .toList();
        return new RetainedLightBatch(slot, x, y, z, absolute);
    }

    private static LightDescriptor.Rectangle rectangle(double area, double radiance) {
        return new LightDescriptor.Rectangle(0, 0, 0, 0,
                0.5, 0, 0, 0, area * 0.5, 0, 0, 0, 1,
                radiance, radiance, radiance);
    }

    private static LightDescriptor.Rectangle translate(LightDescriptor.Finite descriptor,
                                                        double x, double y, double z) {
        LightDescriptor.Rectangle light = (LightDescriptor.Rectangle) descriptor;
        return new LightDescriptor.Rectangle(light.key(), light.positionX() + x,
                light.positionY() + y, light.positionZ() + z,
                light.halfUx(), light.halfUy(), light.halfUz(),
                light.halfVx(), light.halfVy(), light.halfVz(),
                light.normalX(), light.normalY(), light.normalZ(),
                light.radianceRedCdM2(), light.radianceGreenCdM2(), light.radianceBlueCdM2());
    }

    private static double aliasProbability(RtRetainedLightSceneBuilder.AliasData data,
                                           int first, int count, int targetLocalIndex) {
        double probability = 0.0;
        for (int column = 0; column < count; column++) {
            int index = first + column;
            if (column == targetLocalIndex) probability += data.accept()[index] / count;
            if (data.aliasIndices()[index] == targetLocalIndex) {
                probability += (1.0 - data.accept()[index]) / count;
            }
        }
        return probability;
    }

    private static double spanAliasProbability(RtRetainedLightGrid.Data data, int first, int count,
                                               int targetFirstLight) {
        double probability = 0.0;
        for (int column = 0; column < count; column++) {
            int span = first + column;
            if (data.spanFirstLights()[span] == targetFirstLight) {
                probability += data.spanAccept()[span] / count;
            }
            if (data.spanAliasFirstLights()[span] == targetFirstLight) {
                probability += (1.0 - data.spanAccept()[span]) / count;
            }
        }
        return probability;
    }

    private static void assertPackedCoord(float packedFloat, int x, int y, int z) {
        int packed = Float.floatToRawIntBits(packedFloat);
        assertEquals(x, packed & 0x3ff);
        assertEquals(y, (packed >>> 10) & 0x3ff);
        assertEquals(z, (packed >>> 20) & 0x3ff);
    }
}
