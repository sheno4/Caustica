package dev.comfyfluffy.caustica.rt.shader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class RendererShaderNeutralityTest {
    private static final List<String> REMOVED_IDENTIFIERS = List.of(
            "DIMENSION_ULTRAWARM",
            "environmentTemperature",
            "environmentHumidity",
            "proceduralDomainAnchor",
            "PAYLOAD_SHOW_CELESTIAL",
            "showCelestialBodies",
            "caustica_sky_atmosphere",
            "caustica_sky_bindings",
            "caustica_sky_slot",
            "caustica_celestial_dressing",
            "LutSky");

    @Test
    void rendererOwnedShadersExcludeHostSkyVocabularyAndRemovedAbiNames() throws IOException {
        Path root = Path.of("src", "main", "resources", "caustica", "shaders")
                .toAbsolutePath().normalize();
        List<Path> rendererRoots = List.of(root.resolve("api"), root.resolve("world"),
                root.resolve("builtin"));
        List<String> violations = new ArrayList<>();
        for (Path rendererRoot : rendererRoots) {
            try (var paths = Files.walk(rendererRoot)) {
                for (Path source : paths.filter(path -> path.toString().endsWith(".slang")).toList()) {
                    List<String> lines = Files.readAllLines(source);
                    for (int line = 0; line < lines.size(); line++) {
                        String text = lines.get(line);
                        String lower = text.toLowerCase(Locale.ROOT);
                        if (lower.contains("minecraft") || lower.contains("vanilla")
                                || lower.contains("overworld") || lower.contains("biome")) {
                            violations.add(root.relativize(source) + ":" + (line + 1) + ": " + text);
                            continue;
                        }
                        for (String identifier : REMOVED_IDENTIFIERS) {
                            if (text.contains(identifier)) {
                                violations.add(root.relativize(source) + ":" + (line + 1) + ": " + text);
                                break;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "renderer shader crossed its host-neutral content firewall:\n"
                + String.join("\n", violations));
    }
}
