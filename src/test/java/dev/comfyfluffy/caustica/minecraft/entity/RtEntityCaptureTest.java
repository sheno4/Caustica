package dev.comfyfluffy.caustica.minecraft.entity;

import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtEntityCaptureTest {
    private static final float[] X = {0f, 1f, 1f, 0f};
    private static final float[] Y = {0f, 0f, 1f, 1f};
    private static final float[] Z = {0f, 0f, 0f, 0f};
    private static final float[] U = {0f, 1f, 1f, 0f};
    private static final float[] V = {0f, 0f, 1f, 1f};

    @Test
    void packsTrianglesIntoFixedSbtClassOrder() {
        RtEntityCapture capture = capture();
        addQuad(capture, RtAccel.CLASS_MASKED, 22);
        addQuad(capture, RtAccel.CLASS_OPAQUE, 11);
        addQuad(capture, RtAccel.CLASS_MASKED, 33);

        RtEntityCapture.PackedGeometry packed = capture.packGeometry();

        assertArrayEquals(new int[] {2, 4, 0}, packed.classTris());
        assertArrayEquals(new int[] {
                4, 5, 6, 4, 6, 7,
                0, 1, 2, 0, 2, 3,
                8, 9, 10, 8, 10, 11
        }, packed.indices().toIntArray());
        assertMaterialIds(packed, 11, 11, 22, 22, 33, 33);
    }

    @Test
    void resetClearsClassMetadata() {
        RtEntityCapture capture = capture();
        addQuad(capture, RtAccel.CLASS_OPAQUE, 11);

        capture.reset();

        assertArrayEquals(new int[] {0, 0, 0}, capture.packGeometry().classTris());
    }

    /** Capture with no GPU material table: keep the base material as-is instead of resolving a variant. */
    private static RtEntityCapture capture() {
        RtEntityCapture capture = new RtEntityCapture();
        capture.baseColorMaterialResolver = (materialId, baseColorTextureIndex) -> materialId;
        return capture;
    }

    private static void addQuad(RtEntityCapture capture, int cls, int materialId) {
        capture.currentSbtClass = cls;
        capture.currentMaterialId = materialId;
        capture.addDirectQuad(X, Y, Z, U, V, 0f, 0f, 1f, -1);
    }

    private static void assertMaterialIds(RtEntityCapture.PackedGeometry packed, int... expected) {
        assertEquals(expected.length * 12, packed.primitives().size());
        for (int triangle = 0; triangle < expected.length; triangle++) {
            int actual = Float.floatToRawIntBits(packed.primitives().getFloat(triangle * 12 + 8));
            assertEquals(expected[triangle], actual, "material id for packed triangle " + triangle);
        }
    }
}
