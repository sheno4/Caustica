package dev.comfyfluffy.caustica.renderer.raytracing.accel;

import dev.comfyfluffy.caustica.api.geometry.OpacityMicromap;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT;

class RtOpacityMicromapTest {
    @Test void sizeQueryBuildAndBlasAttachmentDescribeTheSameNonemptyUsage() {
        var input = new OpacityMicromap(3,3,new byte[48]);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var info = RtOpacityMicromap.buildInfo(stack, input).get(0);
            var attachment = RtOpacityMicromap.attachment(stack, input, 123);
            assertEquals(1, info.usageCountsCount());
            assertEquals(1, attachment.usageCountsCount());
            assertEquals(3, info.pUsageCounts().get(0).count());
            assertEquals(3, attachment.pUsageCounts().get(0).count());
            assertEquals(3, info.pUsageCounts().get(0).subdivisionLevel());
            assertEquals(VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT, info.pUsageCounts().get(0).format());
            assertEquals(0, attachment.indexBuffer().deviceAddress());
            assertEquals(0, attachment.baseTriangle());
            assertEquals(123, attachment.micromap());
        }
    }
    @Test void triangleDescriptorsUseByteOffsetsAndFourStateFormat() {
        var input = new OpacityMicromap(3,3,new byte[48]);
        var bytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        RtOpacityMicromap.writeTriangles(input, bytes);
        bytes.flip();
        for (int triangle = 0; triangle < 3; triangle++) {
            assertEquals(triangle * 16, bytes.getInt());
            assertEquals(3, bytes.getShort());
            assertEquals(VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT, bytes.getShort());
        }
    }
}
