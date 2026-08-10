package dev.comfyfluffy.caustica.builtin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuiltinImportFirewallTest {
    @Test
    void rendererBuiltinsDoNotReferenceMinecraftAdapters() throws IOException {
        Path sources = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica", "builtin")
                .toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(sources), "builtin source package is missing: " + sources);

        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(sources)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(source);
                for (int line = 0; line < lines.size(); line++) {
                    String text = lines.get(line);
                    if (text.contains("net.minecraft.") || text.contains("net.fabricmc.")
                            || text.contains("com.mojang.")
                            || text.contains("dev.comfyfluffy.caustica.minecraft.")) {
                        violations.add(sources.relativize(source) + ":" + (line + 1) + ": " + text);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "renderer builtin crossed the Minecraft import firewall:\n"
                + String.join("\n", violations));
    }

    @Test
    void rendererBuiltinShadersDoNotContainHostAuthoredSkyCode() throws IOException {
        Path sources = Path.of("src", "main", "resources", "caustica", "shaders", "builtin")
                .toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(sources), "builtin shader source package is missing: " + sources);

        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(sources)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".slang")).toList()) {
                List<String> lines = Files.readAllLines(source);
                for (int line = 0; line < lines.size(); line++) {
                    String text = lines.get(line);
                    String lower = text.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("minecraft") || lower.contains("vanilla")
                            || lower.contains("overworld") || lower.contains("biome")
                            || text.contains("caustica_minecraft_")) {
                        violations.add(sources.relativize(source) + ":" + (line + 1) + ": " + text);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "renderer builtin shader crossed the host-content firewall:\n"
                + String.join("\n", violations));
    }
}
