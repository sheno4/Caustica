package dev.comfyfluffy.caustica.rt.provider;

import dev.comfyfluffy.caustica.SourceRoots;
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
        List<Path> sources = SourceRoots.javaSources("rt", "provider");
        assertTrue(!sources.isEmpty(), "provider runtime package is missing");

        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            if (source.getFileName().toString().startsWith("Minecraft")) {
                violations.add(source.getFileName() + ": Minecraft adapter class");
            }
            for (String line : Files.readAllLines(source)) {
                if (line.startsWith("import ") && (line.contains("net.minecraft.")
                        || line.contains("net.fabricmc.")
                        || line.contains("net.neoforged.")
                        || line.contains("dev.comfyfluffy.caustica.minecraft."))) {
                    violations.add(source.getFileName() + ": " + line);
                }
            }
        }
        assertTrue(violations.isEmpty(), "generic provider runtime crossed the Minecraft firewall:\n"
                + String.join("\n", violations));
    }
}
