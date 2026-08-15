package dev.comfyfluffy.caustica.rt.accel;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkMicromapTriangleEXT;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void gpuTriangleDescriptorsDeclareFourStateEncoding() throws Exception {
        assertEquals(2, VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
        String source = Files.readString(Path.of("shaders/pipelines/opacity_micromap/main.comp.slang"));
        assertTrue(source.contains("static const uint OMM_FORMAT_4_STATE = 2u;"));
        assertTrue(source.contains("(OMM_FORMAT_4_STATE << 16u) | pc.subdivisionLevel"));
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

    @Test
    void temporalPageDescriptorWritePublishesTheBoundArrayLength() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/accel/RtOpacityMicromapPipeline.java"));
        assertTrue(source.contains(".descriptorCount(temporalAlphaViews.length).pImageInfo(images)"));
    }
}
