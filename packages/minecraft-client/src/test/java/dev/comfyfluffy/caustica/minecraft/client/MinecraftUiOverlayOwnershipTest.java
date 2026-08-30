package dev.comfyfluffy.caustica.minecraft.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.Test;


final class MinecraftUiOverlayOwnershipTest {
    @Test
    void mutableOverlayStateBelongsToEachInstance() {
        assertFalse(isStatic(field("runtime")));
        assertFalse(isStatic(field("overlay")));
        assertFalse(isStatic(field("usedThisFrame")));
        assertFalse(isStatic(field("compositeFailed")));
        assertFalse(isStatic(field("overlayClearedThisFrame")));
        assertFalse(isStatic(field("borrowedImage")));
    }

    @Test
    void classLevelStateIsImmutable() {
        Arrays.stream(MinecraftUiOverlay.class.getDeclaredFields())
                .filter(MinecraftUiOverlayOwnershipTest::isStatic)
                .forEach(field -> assertTrue(Modifier.isFinal(field.getModifiers()), field::getName));
    }

    @Test
    void overlayRequiresItsOwningRuntime() throws ReflectiveOperationException {
        assertTrue(Modifier.isPublic(
                MinecraftUiOverlay.class.getConstructor(MinecraftRtRuntime.class).getModifiers()));
    }

    private static Field field(String name) {
        try {
            return MinecraftUiOverlay.class.getDeclaredField(name);
        } catch (NoSuchFieldException exception) {
            throw new AssertionError(exception);
        }
    }

    private static boolean isStatic(Field field) {
        return Modifier.isStatic(field.getModifiers());
    }
}
