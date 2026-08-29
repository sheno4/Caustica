package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NvidiaNgxLifecycleSourceTest {
    @Test
    void worldRendererOnlyInvalidatesSessionOwnedPresentation() throws IOException {
        String renderer = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtFrameRenderer.java"));
        assertTrue(renderer.contains("presenter.invalidateRenderedFrame()"));
        assertFalse(renderer.contains("presenter.destroyGpuResources()"));
    }
}
