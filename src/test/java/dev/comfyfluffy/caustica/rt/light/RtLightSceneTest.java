package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.engine.light.LightDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtLightSceneTest {
    @Test
    void distantRecordCarriesNormalizedDirectionIntegratedLuxAndSelectionMetric() {
        float[] record = new float[RtRetainedLightSceneBuilder.GPU_FLOATS_PER_LIGHT];
        RtRetainedLightSceneBuilder.encode(record, 0, new LightDescriptor.Distant(9,
                0, 3, 4, 100_000, 90_000, 80_000, Math.PI / 3.0),
                100, 64, -10, 2.0);

        assertEquals(3, Float.floatToRawIntBits(record[3]));
        assertEquals(0.6f, record[5], 1.0e-6f);
        assertEquals(0.8f, record[6], 1.0e-6f);
        assertEquals(0.5f, record[11], 1.0e-6f);
        assertEquals(100_000f, record[12]);
        assertEquals(80_000f, record[14]);
        assertEquals(0.27222872f * 100_000f + 0.67408177f * 90_000f
                + 0.05368952f * 80_000f, record[15], 0.02f);
    }

    @Test
    void frameArenaCapacityGrowsByPowersOfTwo() {
        assertEquals(64 * 1024, RtLightScene.capacityForBytes(0));
        assertEquals(64 * 1024, RtLightScene.capacityForBytes(64 * 1024));
        assertEquals(128 * 1024, RtLightScene.capacityForBytes(64 * 1024L + 1));
    }

    @Test
    void spotlightRecordPropagatesPhysicalScaleAndRebasesOnlyPosition() {
        float[] record = new float[RtRetainedLightSceneBuilder.GPU_FLOATS_PER_LIGHT];
        RtRetainedLightSceneBuilder.encode(record, 0, new LightDescriptor.Spot(7,
                105, 66, -12, 0, 0, -2,
                24, Math.PI / 3.0, 100, 80, 60), 100, 64, -10, 2.0);

        assertEquals(5f, record[0]);
        assertEquals(2f, record[1]);
        assertEquals(-2f, record[2]);
        assertEquals(2, Float.floatToRawIntBits(record[3]));
        assertEquals(-1f, record[6]);
        assertEquals(12f, record[7]);
        assertEquals(0.5f, record[11], 1.0e-6f);
        assertEquals(100f, record[12]);
        assertEquals(60f, record[14]);
    }
}
