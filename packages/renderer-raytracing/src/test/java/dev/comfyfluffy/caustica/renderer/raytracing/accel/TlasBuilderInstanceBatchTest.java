package dev.comfyfluffy.caustica.renderer.raytracing.accel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

final class TlasBuilderInstanceBatchTest {
    @Test
    void growthKeepsEveryStructureOfArraysLaneAligned() {
        TlasBuilder.InstanceBatch batch = new TlasBuilder.InstanceBatch();
        batch.reset(1);
        float[] initialTransforms = batch.transforms;
        long[] initialAddresses = batch.blasDeviceAddresses;

        for (int i = 0; i < 17; i++) {
            float[] transform = transform(i);
            batch.append(transform, 0.25f, -0.5f, 0.75f,
                    10_000L + i, 100 + i, 0x80 + i, 200 + i);
        }

        assertEquals(17, batch.size());
        assertNotSame(initialTransforms, batch.transforms);
        assertNotSame(initialAddresses, batch.blasDeviceAddresses);
        for (int i = 0; i < batch.size(); i++) {
            int offset = i * 12;
            assertEquals(i, batch.transforms[offset]);
            assertEquals(i + 3.25f, batch.transforms[offset + 3]);
            assertEquals(i + 6.5f, batch.transforms[offset + 7]);
            assertEquals(i + 11.75f, batch.transforms[offset + 11]);
            assertEquals(10_000L + i, batch.blasDeviceAddresses[i]);
            assertEquals(100 + i, batch.customIndices[i]);
            assertEquals(0x80 + i, batch.masks[i]);
            assertEquals(200 + i, batch.sbtRecordOffsets[i]);
        }
    }

    @Test
    void resetReusesCapacityAndOverwritesFromRecordZero() {
        TlasBuilder.InstanceBatch batch = new TlasBuilder.InstanceBatch();
        batch.reset(20);
        batch.append(transform(1), 0f, 0f, 0f, 11L, 1, 2, 3);
        float[] transforms = batch.transforms;
        long[] addresses = batch.blasDeviceAddresses;
        int[] customIndices = batch.customIndices;
        int[] masks = batch.masks;
        int[] sbtOffsets = batch.sbtRecordOffsets;

        batch.reset(4);
        assertEquals(0, batch.size());
        assertSame(transforms, batch.transforms);
        assertSame(addresses, batch.blasDeviceAddresses);
        assertSame(customIndices, batch.customIndices);
        assertSame(masks, batch.masks);
        assertSame(sbtOffsets, batch.sbtRecordOffsets);

        batch.append(transform(9), 1f, 2f, 3f, 99L, 7, 8, 9);
        assertEquals(1, batch.size());
        assertEquals(9f, batch.transforms[0]);
        assertEquals(13f, batch.transforms[3]);
        assertEquals(18f, batch.transforms[7]);
        assertEquals(23f, batch.transforms[11]);
        assertEquals(99L, batch.blasDeviceAddresses[0]);
        assertEquals(7, batch.customIndices[0]);
        assertEquals(8, batch.masks[0]);
        assertEquals(9, batch.sbtRecordOffsets[0]);
    }

    private static float[] transform(int base) {
        return new float[] {
                base, base + 1, base + 2, base + 3,
                base + 4, base + 5, base + 6, base + 7,
                base + 8, base + 9, base + 10, base + 11
        };
    }
}
