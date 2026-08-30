package dev.comfyfluffy.caustica.minecraft.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftDebugCaptureTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void consumesRequestAfterTheConfiguredNumberOfActiveFrames() throws Exception {
        Path request = temporaryDirectory.resolve("capture.request");
        Files.writeString(request, "3");
        MinecraftDebugCapture capture = new MinecraftDebugCapture(request);

        assertFalse(capture.shouldCapture(false));
        assertTrue(Files.exists(request));
        assertFalse(capture.shouldCapture(true));
        assertFalse(Files.exists(request));
        assertFalse(capture.shouldCapture(true));
        assertTrue(capture.shouldCapture(true));
        assertFalse(capture.shouldCapture(true));
    }
}
