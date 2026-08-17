package dev.comfyfluffy.caustica.engine.color;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class ColorTransformsTest {
    private static final double EPSILON = 1.0e-7;

    @Test
    void decodesSrgbTransferFunction() {
        assertEquals(0.0, ColorTransforms.srgbToLinear(0.0), EPSILON);
        assertEquals(0.003130805, ColorTransforms.srgbToLinear(0.04045), EPSILON);
        assertEquals(1.0, ColorTransforms.srgbToLinear(1.0), EPSILON);
    }

    @Test
    void transformsLinearBt709PrimariesToAcesCg() {
        assertArrayEquals(new float[]{0.61309743f, 0.07019372f, 0.02061559f},
                ColorTransforms.linearBt709ToAcesCg(1.0, 0.0, 0.0));
        assertArrayEquals(new float[]{1.0f, 1.0f, 1.0f},
                ColorTransforms.linearBt709ToAcesCg(1.0, 1.0, 1.0), 1.0e-7f);
    }
}
