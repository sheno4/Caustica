package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.light.RetainedLightBatch;

import dev.comfyfluffy.caustica.engine.light.LightBvh;
import dev.comfyfluffy.caustica.engine.light.LightDescriptor;
import dev.comfyfluffy.caustica.rt.gen.GpuLightData;
import dev.comfyfluffy.caustica.rt.gen.GpuLightNodeData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtRetainedLightSceneBuilderTest {
    private static final ResourceId SOURCE = ResourceId.of("test", "lights");
    @Test
    void lightAndNodeStridesArePinnedToReflectedStd430Layout() {
        assertEquals(64, GpuLightData.BYTE_SIZE);
        assertEquals(48, GpuLightNodeData.BYTE_SIZE);
        assertEquals(GpuLightData.BYTE_SIZE,
                RtRetainedLightSceneBuilder.GPU_FLOATS_PER_LIGHT * Float.BYTES);
        assertEquals(GpuLightNodeData.BYTE_SIZE,
                RtRetainedLightSceneBuilder.GPU_FLOATS_PER_NODE * Float.BYTES);
    }

    @Test
    void bvhLeafIndicesAddressFilteredEncodedLightOrder() {
        LightDescriptor.Rectangle first = rectangle(1, 2, 10);
        LightDescriptor.Rectangle zero = rectangle(2, 0, 20);
        LightDescriptor.Rectangle last = rectangle(3, 4, 30);
        RtRetainedLightSceneBuilder.Data data = RtRetainedLightSceneBuilder.build(List.of(
                new RetainedLightBatch(SOURCE, 0L, 1L, List.of(first, zero, last))),
                8, 0, 0, 2.0, () -> false);

        assertEquals(2, data.lightCount());
        assertEquals(2, data.lightBvh().lights().size());
        assertEquals(3, data.lightBvh().nodes().size());
        int stride = RtRetainedLightSceneBuilder.GPU_FLOATS_PER_LIGHT;
        assertEquals(2f, data.packedLights()[12]);
        assertEquals(4f, data.packedLights()[stride + 12]);
        boolean[] seen = new boolean[2];
        for (LightBvh.Node node : data.lightBvh().nodes()) {
            if (!node.isLeaf()) continue;
            assertTrue(node.lightIndex() >= 0 && node.lightIndex() < 2);
            seen[node.lightIndex()] = true;
            assertEquals(data.lightBvh().lights().get(node.lightIndex()).descriptor().positionX()
                            - data.rebaseX(),
                    data.packedLights()[node.lightIndex() * stride], 0.0);
        }
        assertTrue(seen[0] && seen[1]);
    }

    /**
     * The node's photometric lane is peak intensity, not emitted power: the shader divides it by squared
     * distance to reach lux and compares the result against a distant light's illuminance, so shipping
     * power here would rank every area emitter against every delta source by a factor of pi.
     */
    @Test
    void nodeEncodingCarriesRelativeBoundsPeakIntensityAndTreeLinks() {
        RtRetainedLightSceneBuilder.Data data = RtRetainedLightSceneBuilder.build(List.of(
                new RetainedLightBatch(SOURCE, 0L, 1L,
                        List.of(rectangle(1, 1, 10), rectangle(2, 3, 20)))),
                8, 0, 0, 1.0, () -> false);
        int rootOffset = data.rootNodeIndex() * RtRetainedLightSceneBuilder.GPU_FLOATS_PER_NODE;
        float[] nodes = data.packedNodes();
        LightBvh.Node root = data.lightBvh().nodes().get(data.rootNodeIndex());
        assertTrue(Float.floatToRawIntBits(nodes[rootOffset + 3]) >= 0);
        assertTrue(Float.floatToRawIntBits(nodes[rootOffset + 7]) >= 0);
        assertEquals(-1, Float.floatToRawIntBits(nodes[rootOffset + 9]));
        assertEquals(root.peakLuminousIntensityCandela(), nodes[rootOffset + 8], 1.0e-5);
        assertEquals(data.lightBvh().totalLuminousPowerLumens(),
                Math.PI * nodes[rootOffset + 8], 1.0e-5);
    }

    @Test
    void rebaseChangesCoordinatesButNotPhysicalPower() {
        List<RetainedLightBatch> batches = List.of(new RetainedLightBatch(SOURCE, 0L, 1L,
                List.of(rectangle(1, 1, 20))));
        var oldGeneration = RtRetainedLightSceneBuilder.build(batches, 16, 0, 0, 1.0, () -> false);
        var newGeneration = RtRetainedLightSceneBuilder.build(batches, 32, 0, 0, 1.0, () -> false);
        assertEquals(newGeneration.packedLights()[0], oldGeneration.packedLights()[0] - 16f);
        assertEquals(oldGeneration.packedLights()[15], newGeneration.packedLights()[15]);
    }

    @Test
    void supersededBuildStopsCooperatively() {
        List<RetainedLightBatch> batches = List.of(new RetainedLightBatch(SOURCE, 0L, 1L,
                List.of(rectangle(1, 1, 0))));
        assertThrows(CancellationException.class,
                () -> RtRetainedLightSceneBuilder.build(batches, 0, 0, 0, 1.0, () -> true));
    }

    private static LightDescriptor.Rectangle rectangle(long key, double radiance, double x) {
        return new LightDescriptor.Rectangle(key, x, 0, 0,
                0.5, 0, 0, 0, 0.5, 0, 0, 0, 1,
                radiance, radiance, radiance);
    }
}
