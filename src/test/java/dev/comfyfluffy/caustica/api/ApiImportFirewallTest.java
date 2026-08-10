package dev.comfyfluffy.caustica.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ApiImportFirewallTest {
    @Test
    void publicApiDoesNotImportHostOrBundledImplementationCode() throws IOException {
        Path apiSources = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica", "api")
                .toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(apiSources), "API source package is missing: " + apiSources);

        List<String> forbidden = List.of(
                "Minecraft",
                "net.minecraft.",
                "net.fabricmc.",
                "com.mojang.",
                "dev.comfyfluffy.caustica.minecraft.",
                "dev.comfyfluffy.caustica.builtin.");
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(apiSources)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(source);
                for (int line = 0; line < lines.size(); line++) {
                    String text = lines.get(line);
                    for (String dependency : forbidden) {
                        if (text.contains(dependency)) {
                            violations.add(apiSources.relativize(source) + ":" + (line + 1) + ": " + text);
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "public API crossed its host import firewall:\n"
                + String.join("\n", violations));
    }

    @Test
    void shaderApiUsesHostNeutralVocabulary() throws IOException {
        Path shaderApi = Path.of("src", "main", "resources", "caustica", "shaders", "api")
                .toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(shaderApi), "shader API source package is missing: " + shaderApi);

        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(shaderApi)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".slang")).toList()) {
                List<String> lines = Files.readAllLines(source);
                for (int line = 0; line < lines.size(); line++) {
                    String text = lines.get(line);
                    if (text.contains("Minecraft") || text.contains("biome")) {
                        violations.add(shaderApi.relativize(source) + ":" + (line + 1) + ": " + text);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "shader API crossed its host vocabulary firewall:\n"
                + String.join("\n", violations));
    }
}
