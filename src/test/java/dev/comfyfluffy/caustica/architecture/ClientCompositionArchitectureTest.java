package dev.comfyfluffy.caustica.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientCompositionArchitectureTest {
    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void runtimeAndPlatformDoNotExposeLegacyStaticLocators() throws IOException {
        String runtime = Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtRuntime.java"));
        String platform = Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/dev/comfyfluffy/caustica/platform/CausticaPlatform.java"));

        assertFalse(runtime.contains("RtRuntime INSTANCE"));
        assertFalse(runtime.contains("public static boolean active("));
        assertFalse(runtime.contains("public static boolean frameActive("));
        assertFalse(runtime.contains("public static boolean hasSession("));
        assertFalse(platform.contains("static CausticaPlatform current("));
        assertFalse(platform.contains("static void install("));
    }

    @Test
    void bothLoadersPassTheirPlatformDirectlyToCommonInitialization() throws IOException {
        String fabric = Files.readString(PROJECT_ROOT.resolve(
                "src/fabric/java/dev/comfyfluffy/caustica/FabricCausticaMod.java"));
        String neoforge = Files.readString(PROJECT_ROOT.resolve(
                "src/neoforge/java/dev/comfyfluffy/caustica/NeoForgeCausticaMod.java"));

        assertTrue(fabric.contains("CausticaMod.initialize(platform)"));
        assertTrue(neoforge.contains("CausticaMod.initialize(platform)"));
        assertFalse(fabric.contains("CausticaPlatform.install"));
        assertFalse(neoforge.contains("CausticaPlatform.install"));
    }

    private static Path findProjectRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("src/main/java"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) throw new IllegalStateException("Could not locate project root");
        return candidate;
    }
}
