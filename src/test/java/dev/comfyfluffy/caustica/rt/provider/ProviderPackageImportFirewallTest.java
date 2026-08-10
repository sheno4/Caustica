package dev.comfyfluffy.caustica.rt.provider;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProviderPackageImportFirewallTest {
    @Test
    void genericProviderRuntimeDoesNotContainMinecraftAdapters() throws IOException {
        Path sources = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica",
                "rt", "provider").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(sources), "provider runtime package is missing: " + sources);

        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(sources)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (source.getFileName().toString().startsWith("Minecraft")) {
                    violations.add(sources.relativize(source) + ": Minecraft adapter class");
                }
                for (String line : Files.readAllLines(source)) {
                    if (line.startsWith("import ") && (line.contains("net.minecraft.")
                            || line.contains("net.fabricmc.")
                            || line.contains("dev.comfyfluffy.caustica.minecraft."))) {
                        violations.add(sources.relativize(source) + ": " + line);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "generic provider runtime crossed the Minecraft firewall:\n"
                + String.join("\n", violations));
    }
}
