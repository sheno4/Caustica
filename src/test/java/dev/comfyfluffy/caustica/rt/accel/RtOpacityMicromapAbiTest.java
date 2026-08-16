package dev.comfyfluffy.caustica.rt.accel;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkMicromapTriangleEXT;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT;

final class RtOpacityMicromapAbiTest {
    @Test
    void gpuTriangleDescriptorsMatchVulkanAbi() {
        assertEquals(8, VkMicromapTriangleEXT.SIZEOF);
        assertEquals(0, VkMicromapTriangleEXT.DATAOFFSET);
        assertEquals(4, VkMicromapTriangleEXT.SUBDIVISIONLEVEL);
        assertEquals(6, VkMicromapTriangleEXT.FORMAT);
    }

    @Test
    void everySupportedLevelUsesWholeWordPerTriangleStorage() {
        for (int level = 0; level <= 4; level++) {
            int microTriangles = 1 << (level * 2);
            int rawBytes = Math.max(1, (microTriangles * 2 + 7) >>> 3);
            RtAccel.OpacityMicromapGpuInput input = new RtAccel.OpacityMicromapGpuInput(
                    1, level, rawBytes, (command, dataAddress, triangleAddress, dataStride) -> { });
            assertEquals(0, input.dataStride() & 3);
            assertEquals(Math.max(4, rawBytes), input.dataStride());
        }
    }

}
