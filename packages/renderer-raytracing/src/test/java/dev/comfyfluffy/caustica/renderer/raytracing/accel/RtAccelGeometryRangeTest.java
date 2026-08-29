package dev.comfyfluffy.caustica.renderer.raytracing.accel;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtAccelGeometryRangeTest {
    @Test
    void nativeRangesKeepGapsAndUseByteOffsetsIntoTheSharedIndexStream() {
        try (var ranges = RtAccel.geometryRangeBuildRanges(List.of(
                    new RtAccel.GeometryRange(3, 6, true),
                    new RtAccel.GeometryRange(15, 12, false)))) {

            assertEquals(2, ranges.capacity());
            assertEquals(2, ranges.get(0).primitiveCount());
            assertEquals(3 * Integer.BYTES, ranges.get(0).primitiveOffset());
            assertEquals(4, ranges.get(1).primitiveCount());
            assertEquals(15 * Integer.BYTES, ranges.get(1).primitiveOffset());
        }
    }

    @Test
    void largeGeometryDescriptionsDoNotConsumeTheFixedLwjglStack() {
        List<RtAccel.GeometryRange> descriptions = new ArrayList<>();
        for (int geometry = 0; geometry < 4_096; geometry++) {
            descriptions.add(new RtAccel.GeometryRange(geometry * 3, 3, (geometry & 1) == 0));
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int stackPointer = stack.getPointer();
            try (var geometries = RtAccel.geometryRangeGeometries(
                    new dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress(0x1000), 12,
                    new dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress(0x2000), 12_288,
                    descriptions);
                 var ranges = RtAccel.geometryRangeBuildRanges(descriptions)) {
                assertEquals(4_096, geometries.capacity());
                assertEquals(4_096, ranges.capacity());
                assertEquals(4_095 * 3 * Integer.BYTES, ranges.get(4_095).primitiveOffset());
                assertEquals(stackPointer, stack.getPointer());
            }
        }
    }
}
