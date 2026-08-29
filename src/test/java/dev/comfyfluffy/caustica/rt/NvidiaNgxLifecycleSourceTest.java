package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NvidiaNgxLifecycleSourceTest {
    @Test
    void shutsNgxDownBeforeDestroyingTheVulkanDevice() throws IOException {
        String runtime = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtRuntime.java"));
        int shutdown = runtime.indexOf("closingNgxRuntime.shutdown()");
        int closeDevice = runtime.indexOf("lifecycle.closeDevice(context)", shutdown);
        int destroyDevice = runtime.indexOf("context.destroy()", closeDevice);
        assertTrue(shutdown >= 0 && closeDevice > shutdown && destroyDevice > closeDevice);
    }

    @Test
    void worldRendererOnlyInvalidatesSessionOwnedPresentation() throws IOException {
        String renderer = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtFrameRenderer.java"));
        assertTrue(renderer.contains("presenter.invalidateRenderedFrame()"));
        assertFalse(renderer.contains("presenter.destroyGpuResources()"));
    }
}
