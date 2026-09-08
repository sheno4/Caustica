package dev.comfyfluffy.caustica.renderer.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import static org.junit.jupiter.api.Assertions.*;

final class RtFrameCaptureTest {
    @Test
    void exposesRgbAndPreservesAlphaWithoutMutatingInput() {
        short[] stored = halves(99, 1, -2, 0.5f, 0.25f, 3, 4, 5, 0.75f, 99);
        for (ShortBuffer buffer : new ShortBuffer[]{ShortBuffer.wrap(stored.clone()),
                ByteBuffer.allocateDirect(stored.length * Short.BYTES).order(ByteOrder.nativeOrder()).asShortBuffer()}) {
            buffer.put(0, stored);
            buffer.position(1).limit(9);
            ShortBuffer input = buffer.asReadOnlyBuffer();

            assertArrayEquals(halves(2, -4, 1, 0.25f, 6, 8, 10, 0.75f),
                    RtFrameCapture.applyResidualExposure(input, 2));
            assertEquals(1, input.position());
            assertEquals(9, input.limit());
            short[] unchanged = new short[stored.length];
            buffer.clear().get(unchanged);
            assertArrayEquals(stored, unchanged);
        }
    }

    @Test
    void saturatesHalfRangeButRetainsInvalidSamplesAndSignedZero() {
        short[] result = RtFrameCapture.applyResidualExposure(ShortBuffer.wrap(
                halves(40000, -40000, -0.0f, 0.5f,
                        Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, 1)), 2);
        assertArrayEquals(halves(65504, -65504, -0.0f, 0.5f), java.util.Arrays.copyOf(result, 4));
        assertEquals(65504, Float.float16ToFloat(result[4]));
        assertEquals(-65504, Float.float16ToFloat(result[5]));
        assertTrue(Float.isNaN(Float.float16ToFloat(result[6])));
        assertEquals(Float.floatToFloat16(1), result[7]);
    }

    private static short[] halves(float... values) {
        short[] result = new short[values.length];
        for (int i = 0; i < values.length; i++) result[i] = Float.floatToFloat16(values[i]);
        return result;
    }
}
