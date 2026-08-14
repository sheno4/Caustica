package dev.comfyfluffy.caustica.engine;

import dev.comfyfluffy.caustica.SourceRoots;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class EngineImportFirewallTest {
    @Test
    void engineSourcesDoNotImportHostOrRendererImplementations() throws IOException {
        List<Path> engineSources = SourceRoots.javaSources("engine");
        assertTrue(!engineSources.isEmpty(), "engine source package is missing");

        List<String> violations = new ArrayList<>();
        for (Path source : engineSources) {
            List<String> lines = Files.readAllLines(source);
            for (int line = 0; line < lines.size(); line++) {
                String text = lines.get(line);
                if (text.contains("net.minecraft.") || text.contains("net.fabricmc.")
                        || text.contains("net.neoforged.")
                        || text.contains("com.mojang.")
                        || text.contains("dev.comfyfluffy.caustica.minecraft.")
                        || text.contains("dev.comfyfluffy.caustica.rt.")) {
                    violations.add(source.getFileName() + ":" + (line + 1) + ": " + text);
                }
            }
        }
        assertTrue(violations.isEmpty(), "engine package crossed the host/renderer import firewall:\n"
                + String.join("\n", violations));
    }
}
