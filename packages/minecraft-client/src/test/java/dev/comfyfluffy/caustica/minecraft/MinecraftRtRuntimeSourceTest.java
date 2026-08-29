package dev.comfyfluffy.caustica.minecraft;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftRtRuntimeSourceTest {
    private static final Path RUNTIME = Path.of(
            "packages/minecraft-client/src/main/java/dev/comfyfluffy/caustica/minecraft/MinecraftRtRuntime.java");

    @Test
    void engineWorldProgressOwnsRetainedBackendProgression() throws IOException {
        String runtime = Files.readString(RUNTIME);
        String services = Files.readString(Path.of(
                "packages/engine/src/main/java/dev/comfyfluffy/caustica/engine/session/EngineSessionServices.java"));

        assertTrue(runtime.contains("world.progress();"));
        assertFalse(runtime.contains("scenes.progress();"));
        assertTrue(services.contains("scenes.progress();"));
    }

    @Test
    void worldContributionsStillDrainBeforeDeviceIdle() throws IOException {
        String runtime = Files.readString(RUNTIME);
        int worldClose = runtime.indexOf("world.close()");
        int deviceIdle = runtime.indexOf("gpuExecutor().drainAndWaitIdle()", worldClose);

        assertTrue(worldClose >= 0 && deviceIdle > worldClose);
    }

    @Test
    void readsOnlyItsCurrentRendererCounter() throws IOException {
        String runtime = Files.readString(RUNTIME);

        assertTrue(runtime.contains("session.renderer.frameCounter()"));
        assertFalse(runtime.contains("RtFrameRenderer.frameCounter()"));
    }

    @Test
    void shutsNgxDownBeforeDestroyingTheVulkanDevice() throws IOException {
        String runtime = Files.readString(RUNTIME);
        int shutdown = runtime.indexOf("closingNgxRuntime.shutdown()");
        int closeDevice = runtime.indexOf("lifecycle.closeDevice(context)", shutdown);
        int destroyDevice = runtime.indexOf("context.destroy()", closeDevice);

        assertTrue(shutdown >= 0 && closeDevice > shutdown && destroyDevice > closeDevice);
    }

    @Test
    void ownsTheFrameActivityGateForExport() throws IOException {
        String runtime = Files.readString(RUNTIME);
        int export = runtime.indexOf("boolean exportLatestResidualExposureExr");
        int nextMethod = runtime.indexOf("public boolean requiresSourceWorldFallback", export);
        String method = runtime.substring(export, nextMethod);

        assertTrue(method.contains("return frameActive && session != null && session.renderer != null"));
    }
}
