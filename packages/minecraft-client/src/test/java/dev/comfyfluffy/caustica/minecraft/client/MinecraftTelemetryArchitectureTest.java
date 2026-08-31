package dev.comfyfluffy.caustica.minecraft.client;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

final class MinecraftTelemetryArchitectureTest {
    @Test
    void instrumentationHasNoMutableGlobalState() {
        assertFalse(List.of(MinecraftTelemetry.class.getDeclaredFields()).stream()
                .anyMatch(field -> Modifier.isStatic(field.getModifiers())
                        && MinecraftTelemetry.Instrumentation.class.isAssignableFrom(field.getType())));
        assertFalse(List.of(MinecraftTelemetry.class.getDeclaredMethods()).stream()
                .anyMatch(method -> method.getName().equals("current") || method.getName().equals("install")));
        assertSame(MinecraftTelemetry.disabled(), MinecraftTelemetry.disabled());
    }

}
