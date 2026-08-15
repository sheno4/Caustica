package dev.comfyfluffy.caustica.minecraft.vulkan;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftDeviceBringupOmmTest {
    @Test
    void extensionAndFeatureAreEnabledOnlyFromQueriedSupport() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/minecraft/vulkan/"
                + "MinecraftDeviceBringup.java"));
        assertTrue(source.contains("device.hasDeviceExtension(VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME)"));
        assertTrue(source.contains("if (support.omm()) addOnce(extensions, VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME)"));
        assertTrue(source.contains("if (support.omm()) features.add(OMM)"));
        assertTrue(source.contains("support.ser(), support.omm()"));
    }
}
