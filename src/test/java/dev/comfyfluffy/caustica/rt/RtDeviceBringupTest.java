package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK10;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtDeviceBringupTest {
    @Test
    void capsOverlaySamplesAtFourAndFallsBackInOrder() {
        assertEquals(VK10.VK_SAMPLE_COUNT_4_BIT, RtDeviceBringup.preferredOverlaySampleCount(
                VK10.VK_SAMPLE_COUNT_8_BIT | VK10.VK_SAMPLE_COUNT_4_BIT | VK10.VK_SAMPLE_COUNT_2_BIT));
        assertEquals(VK10.VK_SAMPLE_COUNT_2_BIT, RtDeviceBringup.preferredOverlaySampleCount(
                VK10.VK_SAMPLE_COUNT_8_BIT | VK10.VK_SAMPLE_COUNT_2_BIT));
        assertEquals(VK10.VK_SAMPLE_COUNT_1_BIT,
                RtDeviceBringup.preferredOverlaySampleCount(VK10.VK_SAMPLE_COUNT_8_BIT));
    }

    @Test
    void rejectsInvalidPublishedPhysicalLimits() {
        assertThrows(IllegalArgumentException.class, () -> new RtDeviceBringup.Capabilities(
                true, false, false, false, false, true, Float.NaN, VK10.VK_SAMPLE_COUNT_4_BIT));
        assertThrows(IllegalArgumentException.class, () -> new RtDeviceBringup.Capabilities(
                true, false, false, false, false, true, 4.0f, 3));
    }

    @Test
    void exposesExactlyTheNegotiatedHostCapabilities() {
        try {
            RtDeviceBringup.publish(new RtDeviceBringup.Capabilities(
                    true, true, false, true, false, true, 8.0f, VK10.VK_SAMPLE_COUNT_4_BIT));
            assertTrue(RtDeviceBringup.rtRequested());
            assertTrue(RtDeviceBringup.serExtEnabled());
            assertFalse(RtDeviceBringup.ommEnabled());
            assertTrue(RtDeviceBringup.reflexEnabled());
            assertFalse(RtDeviceBringup.presentIdEnabled());
            assertTrue(RtDeviceBringup.wideLinesEnabled());
            assertEquals(8.0f, RtDeviceBringup.maxLineWidth());
            assertEquals(VK10.VK_SAMPLE_COUNT_4_BIT, RtDeviceBringup.overlayMsaaSamples());
        } finally {
            RtDeviceBringup.publish(RtDeviceBringup.Capabilities.unavailable());
        }
    }
}
