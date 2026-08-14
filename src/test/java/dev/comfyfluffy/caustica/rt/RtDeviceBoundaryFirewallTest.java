package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class RtDeviceBoundaryFirewallTest {
    private static final List<String> SOURCES = List.of(
            "RtDeviceBringup.java", "RtHdr.java", "VulkanDiagnostics.java");
    private static final List<String> HOST_MARKERS = List.of(
            "com.mojang", "net.minecraft", "net.fabricmc", "net.neoforged", "dev.comfyfluffy.caustica.minecraft",
            "dev.comfyfluffy.caustica.mixin", "VulkanPhysicalDevice", "VulkanDevice", "VulkanUtils",
            "injection.invoke.arg.Args", "Minecraft", "Vanilla", "vanilla", "Blaze3D", "Fabric", "Mojang");

    @Test
    void deviceBoundaryUsesOnlyRawVulkanAndNeutralTypes() throws IOException {
        Path root = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica", "rt")
                .toAbsolutePath().normalize();
        for (String name : SOURCES) {
            String source = Files.readString(root.resolve(name));
            for (String marker : HOST_MARKERS) {
                assertFalse(source.contains(marker), name + " contains host marker " + marker);
            }
        }
    }
}
