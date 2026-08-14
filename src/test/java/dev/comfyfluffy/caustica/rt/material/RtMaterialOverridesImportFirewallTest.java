package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class RtMaterialOverridesImportFirewallTest {
    @Test
    void materialOverridesDoNotDependOnMinecraft() throws IOException {
        Path source = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica", "rt", "material",
                "RtMaterialOverrides.java").toAbsolutePath().normalize();
        String text = Files.readString(source);

        for (String forbidden : new String[]{"net.minecraft.", "net.fabricmc.", "net.neoforged.", "com.mojang.",
                "dev.comfyfluffy.caustica.mixin.", "dev.comfyfluffy.caustica.minecraft."}) {
            assertFalse(text.contains(forbidden),
                    () -> source + " crossed the host import firewall with " + forbidden);
        }
    }
}
