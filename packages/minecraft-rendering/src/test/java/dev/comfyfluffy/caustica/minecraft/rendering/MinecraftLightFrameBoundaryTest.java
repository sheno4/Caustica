package dev.comfyfluffy.caustica.minecraft.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class MinecraftLightFrameBoundaryTest {
    @Test
    void publicFrameComponentsDoNotExposeEngineImplementationTypes() {
        for (var component : MinecraftLightFrame.class.getRecordComponents()) {
            assertFalse(component.getType().getPackageName().startsWith("dev.comfyfluffy.caustica.engine"),
                    component.getName());
        }
    }
}
