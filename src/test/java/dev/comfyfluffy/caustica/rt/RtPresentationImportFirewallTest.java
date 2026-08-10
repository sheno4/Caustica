package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtPresentationImportFirewallTest {
    @Test
    void framePresenterDoesNotImportMinecraftAdapters() throws IOException {
        Path source = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica",
                "rt", "RtFramePresenter.java").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(source), "frame presenter source is missing: " + source);

        List<String> violations = Files.readAllLines(source).stream()
                .filter(line -> line.startsWith("import "))
                .filter(line -> line.contains("com.mojang.")
                        || line.contains("net.minecraft.")
                        || line.contains("net.fabricmc.")
                        || line.contains("dev.comfyfluffy.caustica.client.")
                        || line.contains("dev.comfyfluffy.caustica.mixin.")
                        || line.contains("dev.comfyfluffy.caustica.minecraft."))
                .toList();
        assertTrue(violations.isEmpty(), "frame presentation crossed the Minecraft import firewall:\n"
                + String.join("\n", violations));
    }
}
