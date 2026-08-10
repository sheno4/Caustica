package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtLightGridManagerBoundaryTest {
    @Test
    void debugFocusConvertsWorldCoordinatesAtThePublishedOrigin() {
        RtLightGridManager.DebugFocus focus = new RtLightGridManager.DebugFocus(
                -1_000_000.25, 2048.5, 9_000_000.75);

        assertEquals(-0.25, focus.relativeX(-1_000_000), 0.0);
        assertEquals(0.5, focus.relativeY(2048), 0.0);
        assertEquals(0.75, focus.relativeZ(9_000_000), 0.0);
    }

    @Test
    void managerDoesNotImportMinecraftAdapters() throws IOException {
        Path source = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica",
                "rt", "terrain", "RtLightGridManager.java").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(source), "light grid manager source is missing: " + source);

        List<String> violations = Files.readAllLines(source).stream()
                .filter(line -> line.startsWith("import "))
                .filter(line -> line.contains("net.minecraft.")
                        || line.contains("net.fabricmc.")
                        || line.contains("dev.comfyfluffy.caustica.minecraft."))
                .toList();
        assertTrue(violations.isEmpty(), "light grid manager crossed the Minecraft import firewall:\n"
                + String.join("\n", violations));
    }
}
