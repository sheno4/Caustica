package dev.comfyfluffy.caustica.engine.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SceneOriginTest {
    @Test
    void computesRelativeCoordinatesAfterDoublePrecisionSubtraction() {
        SceneOrigin origin = new SceneOrigin(30_000_000.0, -64.0, -30_000_000.0);

        assertEquals(0.25f, origin.relativeX(30_000_000.25));
        assertEquals(4.0f, origin.relativeY(-60.0));
        assertEquals(-0.5f, origin.relativeZ(-30_000_000.5));
    }

    @Test
    void wrapsNegativeOriginsIntoPositiveProceduralDomain() {
        SceneOrigin origin = new SceneOrigin(-1.0, 0.0, -4097.0);

        assertEquals(4095.0f, origin.wrappedX(4096.0));
        assertEquals(4095.0f, origin.wrappedZ(4096.0));
    }
}
