package dev.comfyfluffy.caustica.minecraft.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class RtScreenshotExporterTest {
    @TempDir Path directory;

    @Test
    void selectsOneBasenameWithoutCreatingEitherFile() {
        assertEquals("capture", RtScreenshotExporter.nextPairedName(directory, "capture"));
        assertFalse(Files.exists(directory.resolve("capture.exr")));
        assertFalse(Files.exists(directory.resolve("capture.png")));
    }

    @Test
    void skipsCollisionsInEitherFormatAndUsesFirstAvailableSuffix() throws IOException {
        Files.writeString(directory.resolve("capture.exr"), "existing EXR");
        Files.writeString(directory.resolve("capture_2.png"), "existing PNG");
        Files.writeString(directory.resolve("capture_4.exr"), "later EXR");
        assertEquals("capture_3", RtScreenshotExporter.nextPairedName(directory, "capture"));
        assertEquals("existing EXR", Files.readString(directory.resolve("capture.exr")));
        assertEquals("existing PNG", Files.readString(directory.resolve("capture_2.png")));
        Files.writeString(directory.resolve("capture_3.png"), "third PNG");
        assertEquals("capture_5", RtScreenshotExporter.nextPairedName(directory, "capture"));
    }
}
