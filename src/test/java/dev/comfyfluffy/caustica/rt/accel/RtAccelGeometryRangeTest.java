package dev.comfyfluffy.caustica.rt.accel;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtAccelGeometryRangeTest {
    @Test
    void nativeRangesKeepGapsAndUseByteOffsetsIntoTheSharedIndexStream() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var ranges = RtAccel.geometryRangeBuildRanges(stack, List.of(
                    new RtAccel.GeometryRange(3, 6, true),
                    new RtAccel.GeometryRange(15, 12, false)));

            assertEquals(2, ranges.capacity());
            assertEquals(2, ranges.get(0).primitiveCount());
            assertEquals(3 * Integer.BYTES, ranges.get(0).primitiveOffset());
            assertEquals(4, ranges.get(1).primitiveCount());
            assertEquals(15 * Integer.BYTES, ranges.get(1).primitiveOffset());
        }
    }
}
