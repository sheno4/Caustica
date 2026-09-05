package dev.comfyfluffy.caustica.renderer.raytracing;

import dev.comfyfluffy.caustica.renderer.raytracing.gen.PackedPathSegmentData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.PackedPathSegmentData.Float3;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.StablePlaneRecordData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtPathQueueAbiTest {
    @Test
    void scratchCapacityUsesThreeReflectedRestartRecordsPerPixel() {
        assertEquals(64, PackedPathSegmentData.BYTE_SIZE);
        assertEquals(1920L * 1080L * 3L * PackedPathSegmentData.BYTE_SIZE,
                TraceResources.pathScratchBytes(1920, 1080));
    }

    @Test
    void restartRecordsWriteExactFieldsAndArrayStride() {
        ByteBuffer storage = ByteBuffer.allocate(2 * PackedPathSegmentData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        for (int record = 0; record < 2; record++) {
            float value = 100f * record;
            new PackedPathSegmentData(new Float3(value + 1, value + 2, value + 3), value + 4,
                    new Float3(value + 5, value + 6, value + 7), value + 8,
                    new Float3(value + 9, value + 10, value + 11), 0x12345678 + record,
                    new Float3(value + 13, value + 14, value + 15), 0x80000001 + record)
                    .write(storage.slice(record * PackedPathSegmentData.BYTE_SIZE,
                            PackedPathSegmentData.BYTE_SIZE).order(ByteOrder.nativeOrder()));
        }
        for (int record = 0; record < 2; record++) {
            int base = record * 64;
            for (int word = 0; word < 16; word++) {
                if (word == 11) {
                    assertEquals(0x12345678 + record, storage.getInt(base + word * 4));
                } else if (word == 15) {
                    assertEquals(0x80000001 + record, storage.getInt(base + word * 4));
                } else {
                    assertEquals(100f * record + word + 1, storage.getFloat(base + word * 4));
                }
            }
        }
    }

    @Test
    void stablePlaneCapacityUsesThreeReflectedRecordsPerPixel() {
        assertEquals(112, StablePlaneRecordData.BYTE_SIZE);
        assertEquals(1920L * 1080L * 3L * StablePlaneRecordData.BYTE_SIZE,
                TraceResources.stablePlaneBytes(1920, 1080));
    }
}
