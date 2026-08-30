package dev.comfyfluffy.caustica.minecraft.client.vulkan;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftHdrTest {
    private static final float EPSILON = 0.000001f;

    @Test
    void buildsRec2020D65MetadataAtTheSelectedAcesMasteringPeak() {
        MinecraftHdr.MasteringMetadata metadata = MinecraftHdr.masteringMetadata(1000);

        assertChromaticity(metadata.red(), 0.708f, 0.292f);
        assertChromaticity(metadata.green(), 0.170f, 0.797f);
        assertChromaticity(metadata.blue(), 0.131f, 0.046f);
        assertChromaticity(metadata.white(), 0.3127f, 0.3290f);
        assertEquals(1000.0f, metadata.maxLuminance(), EPSILON);
        assertEquals(0.0001f, metadata.minLuminance(), EPSILON);
        assertEquals(1000.0f, metadata.maxContentLightLevel(), EPSILON);
        assertEquals(0.0f, metadata.maxFrameAverageLightLevel(), EPSILON);
    }

    @Test
    void rejectsAnInvalidMasteringPeak() {
        assertThrows(IllegalArgumentException.class, () -> MinecraftHdr.masteringMetadata(0));
    }

    @Test
    void retriesSurfaceFormatEnumerationUntilTheReturnedListIsComplete() {
        AtomicInteger calls = new AtomicInteger();
        List<MinecraftHdr.SurfaceFormat> formats = MinecraftHdr.surfaceFormats((count, output) -> {
            return switch (calls.incrementAndGet()) {
                case 1 -> {
                    count.put(0, 1);
                    yield VK10.VK_SUCCESS;
                }
                case 2 -> {
                    putFormat(output.get(0), 44, 0);
                    count.put(0, 2);
                    yield VK10.VK_INCOMPLETE;
                }
                case 3 -> {
                    count.put(0, 2);
                    yield VK10.VK_SUCCESS;
                }
                case 4 -> {
                    putFormat(output.get(0), 44, 0);
                    putFormat(output.get(1), 64, 1000104008);
                    count.put(0, 2);
                    yield VK10.VK_SUCCESS;
                }
                default -> throw new AssertionError("unexpected query call");
            };
        });

        assertEquals(4, calls.get());
        assertEquals(List.of(
                new MinecraftHdr.SurfaceFormat(44, 0),
                new MinecraftHdr.SurfaceFormat(64, 1000104008)), formats);
    }

    private static void assertChromaticity(MinecraftHdr.Chromaticity actual, float x, float y) {
        assertEquals(x, actual.x(), EPSILON);
        assertEquals(y, actual.y(), EPSILON);
    }

    private static void putFormat(VkSurfaceFormatKHR destination, int format, int colorSpace) {
        MemoryUtil.memPutInt(destination.address() + VkSurfaceFormatKHR.FORMAT, format);
        MemoryUtil.memPutInt(destination.address() + VkSurfaceFormatKHR.COLORSPACE, colorSpace);
    }
}
