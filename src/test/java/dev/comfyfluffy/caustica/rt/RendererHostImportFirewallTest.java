package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.SourceRoots;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class RendererHostImportFirewallTest {
    private static final List<String> HOST_PREFIXES = List.of(
            "net.minecraft.",
            "net.fabricmc.",
            "net.neoforged.",
            "com.mojang.",
            "dev.comfyfluffy.caustica.minecraft.",
            "dev.comfyfluffy.caustica.mixin.",
            "dev.comfyfluffy.caustica.client.");

    @Test
    void rendererOwnedJavaHasNoHostImports() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : SourceRoots.javaSources("rt")) {
            int lineNumber = 0;
            for (String line : Files.readAllLines(source)) {
                lineNumber++;
                String imported = importedType(line.trim());
                if (imported != null && HOST_PREFIXES.stream().anyMatch(imported::startsWith)) {
                    violations.add(source.getFileName() + ":" + lineNumber + ": " + line.trim());
                }
            }
        }
        assertTrue(violations.isEmpty(), "renderer crossed the host import firewall:\n"
                + String.join("\n", violations));
    }

    @Test
    void retiredHostPackagesContainNoJavaSources() throws IOException {
        assertTrue(SourceRoots.javaSources("rt", "entity").isEmpty(), "rt/entity still owns Java sources");
        assertTrue(SourceRoots.javaSources("rt", "terrain").isEmpty(), "rt/terrain still owns Java sources");
    }

    private static String importedType(String line) {
        String prefix = line.startsWith("import static ") ? "import static "
                : line.startsWith("import ") ? "import " : null;
        return prefix != null && line.endsWith(";")
                ? line.substring(prefix.length(), line.length() - 1) : null;
    }
}
