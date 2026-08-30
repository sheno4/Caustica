package dev.comfyfluffy.caustica.minecraft.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftClientFrameCaptureTest {
    @Test
    void atlasWarningsAreReportedOncePerFailureReason() {
        var warnings = new MinecraftClientFrameCapture.AtlasWarningTracker();

        assertTrue(warnings.first(MinecraftClientFrameCapture.AtlasFailure.LOOKUP));
        assertFalse(warnings.first(MinecraftClientFrameCapture.AtlasFailure.LOOKUP));
        assertTrue(warnings.first(MinecraftClientFrameCapture.AtlasFailure.NON_VULKAN_VIEW));
        assertTrue(warnings.first(MinecraftClientFrameCapture.AtlasFailure.FORMAT));
        assertFalse(warnings.first(MinecraftClientFrameCapture.AtlasFailure.FORMAT));
    }
}
