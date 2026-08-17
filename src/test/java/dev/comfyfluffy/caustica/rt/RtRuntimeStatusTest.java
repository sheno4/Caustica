package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class RtRuntimeStatusTest {
    @Test
    void inactiveRuntimePublishesDirectStatus() {
        assertEquals(RtRuntime.State.OFF, RtRuntime.INSTANCE.state());
        assertFalse(RtRuntime.active());
        assertFalse(RtRuntime.frameActive());
        assertFalse(RtRuntime.hasSession());
        assertFalse(RtRuntime.INSTANCE.rendererFailed());
        assertEquals(RtRuntime.WorldReplacement.FRAME_INACTIVE, RtRuntime.INSTANCE.worldReplacement());
    }
}
