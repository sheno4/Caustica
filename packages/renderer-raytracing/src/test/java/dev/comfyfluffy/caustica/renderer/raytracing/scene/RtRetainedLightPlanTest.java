package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.ArrayList;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtRetainedLightPlanTest {
    @Test
    void packsEveryPublicLightInAcceptanceOrderAndPhysicalUnits() {
        ByteBuffer records = RtRetainedLightPlan.pack(List.of(
                new LightDescriptor.Parallelogram(11, 22, 33, 2, 0, 0, 0, 3, 0, 4, 5, 6),
                new LightDescriptor.Spot(17, 28, 39, 0, 0, 1,
                        11, 0.2, 12, 13, 14),
                new LightDescriptor.Distant(0, 1, 0, 15, 16, 17, 0.4, false)),
                new SceneOrigin(10, 20, 30)).order(ByteOrder.nativeOrder());

        assertEquals(3 * 80, records.remaining());
        assertRecord(records, 0, RtRetainedLightPlan.PARALLELOGRAM, 1, 2, 3, 0, 0);
        assertEquals(2.0f, records.getFloat(32));
        assertEquals(3.0f, records.getFloat(52));
        assertEquals(4.0f, records.getFloat(64));

        int spot = 80;
        assertRecord(records, spot, RtRetainedLightPlan.SPOT, 7, 8, 9, 11, 0);
        assertEquals(0.2f, records.getFloat(spot + 44));
        assertEquals(0.0f, records.getFloat(spot + 60));
        assertEquals(12.0f, records.getFloat(spot + 64));

        int distant = 160;
        assertRecord(records, distant, RtRetainedLightPlan.DISTANT, 0, 1, 0, 0, 0.4f);
        assertEquals(15.0f, records.getFloat(distant + 64));
    }

    @Test
    void marksOnlyLightsWithActiveGeometryEmitterLinks() {
        var light = new LightDescriptor.Parallelogram(0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 1);
        ByteBuffer records = RtRetainedLightPlan.pack(List.of(light, light),
                new SceneOrigin(0, 0, 0), new boolean[]{true, false});

        assertEquals(1, records.getInt(4));
        assertEquals(0, records.getInt(RtRetainedLightPlan.RECORD_BYTES + 4));
    }

    @Test
    void rejectsPhysicalValuesOutsideGpuFloatRange() {
        var light = new LightDescriptor.Spot(Double.MAX_VALUE, 0, 0, 0, 0, 1,
                1, 0.2, 1, 1, 1);
        assertThrows(IllegalArgumentException.class,
                () -> RtRetainedLightPlan.pack(List.of(light), new SceneOrigin(0, 0, 0)));
    }

    @Test
    void parallelRangesMatchSequentialLightBytesAndGlobalLinkedFlags() {
        List<LightDescriptor> lights = new ArrayList<>();
        int count = 1027;
        boolean[] flags = new boolean[count];
        BitSet linked = new BitSet(count);
        for (int index = 0; index < count; index++) {
            lights.add(switch (index % 3) {
                case 0 -> new LightDescriptor.Parallelogram(index, -index, 33,
                        2, 0, 0, 0, 3, 0, index + 1, 5, 6);
                case 1 -> new LightDescriptor.Spot(index, 28, -index, 0, 0, 1,
                        11, 0.2, 12, index + 1, 14);
                default -> new LightDescriptor.Distant(0, 1, 0, 15, 16, index + 1, 0.4, false);
            });
            flags[index] = index % 5 == 1 || index % 7 == 2;
            if (flags[index]) linked.set(index);
        }
        try (var preparation = new RtFramePreparation()) {
            for (SceneOrigin origin : List.of(new SceneOrigin(0, 0, 0), new SceneOrigin(-17, 31, 4000))) {
                ByteBuffer sequential = RtRetainedLightPlan.pack(lights, origin, flags);
                ByteBuffer parallel = ByteBuffer.allocateDirect(sequential.remaining() + 16);
                for (int index = 0; index < parallel.capacity(); index++) parallel.put(index, (byte) 0x5a);
                List<Runnable> tasks = new ArrayList<>();
                int firstLight = 0;
                for (List<LightDescriptor> chunk : RtFramePreparation.chunks(lights)) {
                    int first = firstLight;
                    tasks.add(RtFramePreparation.measured("lights", 0, chunk.size(), () ->
                            RtRetainedLightPlan.packInto(parallel.slice(8 + first * RtRetainedLightPlan.RECORD_BYTES,
                                            chunk.size() * RtRetainedLightPlan.RECORD_BYTES),
                                    first, chunk.size(), lights::get, origin, linked::get)));
                    firstLight += chunk.size();
                }
                preparation.run(tasks, Runnable::run);
                assertEquals(sequential, parallel.slice(8, sequential.remaining()));
                for (int index = 0; index < 8; index++) {
                    assertEquals((byte) 0x5a, parallel.get(index));
                    assertEquals((byte) 0x5a, parallel.get(parallel.capacity() - 1 - index));
                }
            }
        }
    }

    private static void assertRecord(ByteBuffer records, int base, int type,
                                     float x, float y, float z, float range, float radius) {
        assertEquals(type, records.getInt(base));
        assertEquals(range, records.getFloat(base + 8));
        assertEquals(radius, records.getFloat(base + 12));
        assertEquals(x, records.getFloat(base + 16));
        assertEquals(y, records.getFloat(base + 20));
        assertEquals(z, records.getFloat(base + 24));
    }
}
