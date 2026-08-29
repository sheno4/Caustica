package dev.comfyfluffy.caustica.rt.scene;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtRetainedLightPlanTest {
    @Test
    void packsEveryPublicLightInAcceptanceOrderAndPhysicalUnits() {
        ByteBuffer records = RtRetainedLightPlan.pack(List.of(
                new LightDescriptor.Rectangle(11, 22, 33, 2, 0, 0, 0, 3, 0, 4, 5, 6),
                new LightDescriptor.Spot(17, 28, 39, 0, 0, 1,
                        11, 0.2, 12, 13, 14),
                new LightDescriptor.Distant(0, 1, 0, 15, 16, 17, 0.4, false)),
                new SceneOrigin(10, 20, 30)).order(ByteOrder.nativeOrder());

        assertEquals(3 * 80, records.remaining());
        assertRecord(records, 0, RtRetainedLightPlan.RECTANGLE, 1, 2, 3, 0, 0);
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
        var light = new LightDescriptor.Rectangle(0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 1);
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
