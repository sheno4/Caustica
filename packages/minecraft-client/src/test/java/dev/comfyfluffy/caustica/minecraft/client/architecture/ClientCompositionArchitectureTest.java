package dev.comfyfluffy.caustica.minecraft.client.architecture;

import dev.comfyfluffy.caustica.minecraft.client.TestProjectRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientCompositionArchitectureTest {
    private static final Path PROJECT_ROOT = TestProjectRoot.resolve("");

    @Test
    void runtimeAndPlatformDoNotExposeLegacyStaticLocators() throws IOException {
        String runtime = Files.readString(PROJECT_ROOT.resolve(
                "packages/minecraft-client/src/main/java/dev/comfyfluffy/caustica/minecraft/client/MinecraftRtRuntime.java"));
        String platform = Files.readString(PROJECT_ROOT.resolve(
                "packages/minecraft-client/src/main/java/dev/comfyfluffy/caustica/minecraft/client/platform/CausticaPlatform.java"));

        assertFalse(runtime.contains("MinecraftRtRuntime INSTANCE"));
        assertFalse(runtime.contains("public static boolean active("));
        assertFalse(runtime.contains("public static boolean frameActive("));
        assertFalse(runtime.contains("public static boolean hasSession("));
        assertFalse(platform.contains("static CausticaPlatform current("));
        assertFalse(platform.contains("static void install("));
    }

    @Test
    void bothLoadersPassTheirPlatformDirectlyToCommonInitialization() throws IOException {
        String fabric = Files.readString(PROJECT_ROOT.resolve(
                "packages/minecraft-client/src/fabric/java/dev/comfyfluffy/caustica/minecraft/client/FabricCausticaMod.java"));
        String neoforge = Files.readString(PROJECT_ROOT.resolve(
                "packages/minecraft-client/src/neoforge/java/dev/comfyfluffy/caustica/minecraft/client/NeoForgeCausticaMod.java"));

        assertTrue(fabric.contains("CausticaMod.initialize(platform)"));
        assertTrue(neoforge.contains("CausticaMod.initialize(platform)"));
        assertFalse(fabric.contains("CausticaPlatform.install"));
        assertFalse(neoforge.contains("CausticaPlatform.install"));
    }
}
