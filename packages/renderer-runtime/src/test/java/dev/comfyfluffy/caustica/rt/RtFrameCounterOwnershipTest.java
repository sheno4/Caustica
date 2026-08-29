package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

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

    @Test
    void runtimeReadsOnlyItsCurrentRendererCounter() throws IOException {
        String runtime = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtRuntime.java"));

        assertTrue(runtime.contains("session.renderer.frameCounter()"));
        assertFalse(runtime.contains("RtFrameRenderer.frameCounter()"));
    }
}
