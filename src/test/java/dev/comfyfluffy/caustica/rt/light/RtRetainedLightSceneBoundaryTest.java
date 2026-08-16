package dev.comfyfluffy.caustica.rt.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtRetainedLightSceneBoundaryTest {
    @Test
    void debugFocusConvertsWorldCoordinatesAtThePublishedOrigin() {
        RtRetainedLightScene.DebugFocus focus = new RtRetainedLightScene.DebugFocus(
                -1_000_000.25, 2048.5, 9_000_000.75);

        assertEquals(-0.25, focus.relativeX(-1_000_000), 0.0);
        assertEquals(0.5, focus.relativeY(2048), 0.0);
        assertEquals(0.75, focus.relativeZ(9_000_000), 0.0);
    }

}
