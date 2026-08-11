package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class RendererHostImportFirewallTest {
    private static final Path RENDERER = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica", "rt")
            .toAbsolutePath().normalize();
    private static final List<String> HOST_PREFIXES = List.of(
            "net.minecraft.",
            "net.fabricmc.",
            "com.mojang.",
            "dev.comfyfluffy.caustica.minecraft.",
            "dev.comfyfluffy.caustica.mixin.",
            "dev.comfyfluffy.caustica.client.");

    @Test
    void rendererOwnedJavaHasNoHostImports() throws IOException {
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(RENDERER)) {
            for (Path source : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java")).toList()) {
                int lineNumber = 0;
                for (String line : Files.readAllLines(source)) {
                    lineNumber++;
                    String imported = importedType(line.trim());
                    if (imported != null && HOST_PREFIXES.stream().anyMatch(imported::startsWith)) {
                        violations.add(RENDERER.relativize(source) + ":" + lineNumber + ": " + line.trim());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "renderer crossed the host import firewall:\n"
                + String.join("\n", violations));
    }

    @Test
    void retiredHostPackagesContainNoJavaSources() throws IOException {
        assertTrue(javaSources(RENDERER.resolve("entity")).isEmpty(), "rt/entity still owns Java sources");
        assertTrue(javaSources(RENDERER.resolve("terrain")).isEmpty(), "rt/terrain still owns Java sources");
    }

    private static String importedType(String line) {
        String prefix = line.startsWith("import static ") ? "import static "
                : line.startsWith("import ") ? "import " : null;
        return prefix != null && line.endsWith(";")
                ? line.substring(prefix.length(), line.length() - 1) : null;
    }

    private static List<Path> javaSources(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
