package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtFrameStatsBoundaryTest {
    @Test
    void outputLocationIsAbsoluteLazyAndFixedAfterWriterInitialization(@TempDir Path temporary) {
        Path requested = temporary.resolve("nested").resolve("..").resolve("stats");
        RtFrameStats.OutputLocation output = new RtFrameStats.OutputLocation(Path.of("default-stats"));

        output.configure(requested);

        Path expected = requested.toAbsolutePath().normalize();
        assertEquals(expected, output.directory());
        assertFalse(Files.exists(expected), "configuration must not create the output directory");
        assertEquals(expected, output.beginWriterInitialization());
        assertThrows(IllegalStateException.class, () -> output.configure(temporary.resolve("other")));
    }

    @Test
    void processWorkingDirectoryDefaultIsNormalizedAndAbsolute() {
        Path output = RtFrameStats.defaultOutputDirectory();
        assertTrue(output.isAbsolute());
        assertEquals(output.normalize(), output);
        assertEquals("rt-frame-stats", output.getFileName().toString());
    }

    @Test
    void frameStatsDoesNotImportMinecraftOrFabric() throws IOException {
        Path source = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica",
                "rt", "RtFrameStats.java").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(source), "frame stats source is missing: " + source);

        List<String> violations = Files.readAllLines(source).stream()
                .filter(line -> line.startsWith("import "))
                .filter(line -> line.contains("net.fabricmc.")
                        || line.contains("net.minecraft.")
                        || line.contains("dev.comfyfluffy.caustica.minecraft."))
                .toList();
        assertTrue(violations.isEmpty(), "frame stats crossed the host import firewall:\n"
                + String.join("\n", violations));
    }
}
