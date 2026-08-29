package dev.comfyfluffy.caustica.renderer.raytracing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TraceExtentTest {
    @Test
    void keepsRenderAndDisplayExtentsDistinct() {
        TraceExtent extent = new TraceExtent(1280, 720, 1920, 1080);
        assertEquals(1280, extent.renderWidth());
        assertEquals(1080, extent.displayHeight());
    }

    @Test
    void rejectsEmptyAllocations() {
        assertThrows(IllegalArgumentException.class, () -> new TraceExtent(0, 720, 1920, 1080));
    }
}
