package dev.comfyfluffy.caustica.minecraft.vulkan;

import dev.comfyfluffy.caustica.api.gpu.GpuRasterCapabilities;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK10;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftDeviceBringupTest {
    @Test
    void capsOverlaySamplesAtFourAndFallsBackInOrder() {
        assertEquals(VK10.VK_SAMPLE_COUNT_4_BIT, MinecraftDeviceBringup.preferredOverlaySampleCount(
                VK10.VK_SAMPLE_COUNT_8_BIT | VK10.VK_SAMPLE_COUNT_4_BIT | VK10.VK_SAMPLE_COUNT_2_BIT));
        assertEquals(VK10.VK_SAMPLE_COUNT_2_BIT, MinecraftDeviceBringup.preferredOverlaySampleCount(
                VK10.VK_SAMPLE_COUNT_8_BIT | VK10.VK_SAMPLE_COUNT_2_BIT));
        assertEquals(VK10.VK_SAMPLE_COUNT_1_BIT,
                MinecraftDeviceBringup.preferredOverlaySampleCount(VK10.VK_SAMPLE_COUNT_8_BIT));
    }

    @Test
    void rejectsInvalidRasterLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuRasterCapabilities(true, Float.NaN, VK10.VK_SAMPLE_COUNT_4_BIT));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuRasterCapabilities(true, 4.0f, 3));
    }
}
