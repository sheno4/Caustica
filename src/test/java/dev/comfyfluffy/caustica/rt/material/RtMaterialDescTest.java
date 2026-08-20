package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtMaterialDescTest {
    @Test
    void retainsShaderEmissionLuminance() {
        RtMaterialDesc desc = new RtMaterialDesc(0,
                0.7f, 0.0f, 1.0f, 0.0f, 1.0f, 0);
        assertEquals(1.0f, desc.emissionLuminance());
    }

    /**
     * The compiled binding carries the implementation index in eight bits, so an out-of-range one would
     * alias another registered implementation rather than fail anywhere visible.
     */
    @Test
    void rejectsASurfaceImplementationTheBindingCannotCarry() {
        assertThrows(IllegalArgumentException.class, () -> new RtMaterialDesc(0,
                0.5f, 0.0f, 1.0f, 0.0f,
                0.0f, 256));
    }

    @Test
    void rejectsInvalidPhysicalParameters() {
        assertThrows(IllegalArgumentException.class, () -> new RtMaterialDesc(0,
                1.1f, 0.0f, 1.0f, 0.0f,
                0.0f, 0));
        assertThrows(IllegalArgumentException.class, () -> new RtMaterialDesc(0,
                0.5f, 0.0f, 0.0f, 0.0f,
                0.0f, 0));
    }
}
