package dev.comfyfluffy.caustica.renderer.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtFrameCounterOwnershipTest {
    @Test
    void frameCounterBelongsToEachRenderer() throws Exception {
        var counter = RtFrameRenderer.class.getDeclaredField("frameCounter");
        var accessor = RtFrameRenderer.class.getDeclaredMethod("frameCounter");

        assertFalse(Modifier.isStatic(counter.getModifiers()));
        assertFalse(Modifier.isStatic(accessor.getModifiers()));
        assertTrue(Modifier.isVolatile(counter.getModifiers()));
    }
}
