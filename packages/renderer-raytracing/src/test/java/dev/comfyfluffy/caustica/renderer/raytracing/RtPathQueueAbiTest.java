package dev.comfyfluffy.caustica.renderer.raytracing;

import dev.comfyfluffy.caustica.renderer.raytracing.gen.PackedPathSegmentData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtPathQueueAbiTest {
    @Test
    void queueCapacityUsesTheReflectedPackedRecordStride() {
        assertEquals(48, PackedPathSegmentData.BYTE_SIZE);
        assertEquals(1920L * 1080L * 2L * PackedPathSegmentData.BYTE_SIZE,
                TraceResources.continuationBytes(1920, 1080));
    }
}
