package dev.comfyfluffy.caustica.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class EngineImportFirewallTest {
    @Test
    void engineSourcesDoNotImportMinecraft() throws IOException {
        Path engineSources = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica", "engine")
                .toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(engineSources), "engine source package is missing: " + engineSources);

        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(engineSources)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(source);
                for (int line = 0; line < lines.size(); line++) {
                    String text = lines.get(line);
                    if (text.contains("net.minecraft.")) {
                        violations.add(engineSources.relativize(source) + ":" + (line + 1) + ": " + text);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "engine package crossed the Minecraft import firewall:\n"
                + String.join("\n", violations));
    }
}
