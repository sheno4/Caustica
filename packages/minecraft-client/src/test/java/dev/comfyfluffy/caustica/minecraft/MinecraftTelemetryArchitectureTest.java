package dev.comfyfluffy.caustica.minecraft;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

final class MinecraftTelemetryArchitectureTest {
    private static final Path MINECRAFT = Path.of(
            "packages/minecraft-client/src/main/java/dev/comfyfluffy/caustica/minecraft");

    @Test
    void instrumentationHasNoMutableGlobalState() {
        assertFalse(List.of(MinecraftTelemetry.class.getDeclaredFields()).stream()
                .anyMatch(field -> Modifier.isStatic(field.getModifiers())
                        && MinecraftTelemetry.Instrumentation.class.isAssignableFrom(field.getType())));
        assertFalse(List.of(MinecraftTelemetry.class.getDeclaredMethods()).stream()
                .anyMatch(method -> method.getName().equals("current") || method.getName().equals("install")));
        assertSame(MinecraftTelemetry.disabled(), MinecraftTelemetry.disabled());
    }

    @Test
    void minecraftSourcesDoNotLocateGlobalInstrumentation() throws IOException {
        try (var sources = Files.walk(MINECRAFT)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source);
                assertFalse(text.contains("MinecraftTelemetry.current("), source.toString());
                assertFalse(text.contains("MinecraftTelemetry.install("), source.toString());
            }
        }
    }
}
