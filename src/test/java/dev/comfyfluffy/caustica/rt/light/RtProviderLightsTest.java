package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.engine.light.LightDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtProviderLightsTest {
    @Test
    void distantRecordCarriesNormalizedDirectionIntegratedLuxAndCone() {
        float[] record = RtProviderLights.encode(new LightDescriptor.Distant(9,
                0, 3, 4, 100_000, 90_000, 80_000, Math.PI / 3.0),
                100, 64, -10, 2.0);

        assertEquals(0f, record[0]);
        assertEquals(3, Float.floatToRawIntBits(record[3]));
        assertEquals(0.6f, record[5], 1.0e-6f);
        assertEquals(0.8f, record[6], 1.0e-6f);
        assertEquals(0.5f, record[11], 1.0e-6f);
        assertEquals(100_000f, record[12]);
        assertEquals(80_000f, record[14]);
    }

    @Test
    void providerRingCapacityGrowsByWholeRecords() {
        assertEquals(1024, RtProviderLights.capacityForCount(0));
        assertEquals(1024, RtProviderLights.capacityForCount(1024));
        assertEquals(2048, RtProviderLights.capacityForCount(1025));
        assertEquals(2048L * RtProviderLights.RECORD_BYTES,
                (long) RtProviderLights.capacityForCount(1025) * RtProviderLights.RECORD_BYTES);
    }

    @Test
    void spotlightRecordPreservesMetricPhotometryAndRebasesOnlyPosition() {
        float[] record = RtProviderLights.encode(new LightDescriptor.Spot(7,
                105, 66, -12, 0, 0, -2,
                24, Math.PI / 3.0, 100, 80, 60),
                100, 64, -10, 2.0);

        assertEquals(5f, record[0]);
        assertEquals(2f, record[1]);
        assertEquals(-2f, record[2]);
        assertEquals(2, Float.floatToRawIntBits(record[3]));
        assertEquals(-1f, record[6]);
        assertEquals(12f, record[7]);
        assertEquals(0.5f, record[11], 1.0e-6f);
        assertEquals(100f, record[12]);
        assertEquals(80f, record[13]);
        assertEquals(60f, record[14]);
        assertEquals(2f, record[15]);
    }

    @Test
    void rectangleRecordCarriesAreaAxesRadianceAndAuthoredFacing() {
        float[] record = RtProviderLights.encode(new LightDescriptor.Rectangle(2,
                0, 0, 0,
                2, 0, 0, 0, 3, 0,
                0, 0, -4,
                10, 11, 12), 0, 0, 0, 1.0);

        assertEquals(0, Float.floatToRawIntBits(record[3]));
        assertEquals(2f, record[4]);
        assertEquals(3f, record[9]);
        assertEquals(-1f, record[11]);
        assertEquals(10f, record[12]);
        assertEquals(12f, record[14]);
    }
}
