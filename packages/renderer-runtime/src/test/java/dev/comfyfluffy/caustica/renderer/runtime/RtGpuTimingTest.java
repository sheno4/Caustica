package dev.comfyfluffy.caustica.renderer.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtGpuTimingTest {
    @Test
    void convertsTimestampPeriod() {
        assertEquals(75.0, RtGpuTiming.elapsedNanos(100, 150, 64, 1.5f));
    }

    @Test
    void masksUnusedBitsAndHandlesCounterWrap() {
        assertEquals(16.0, RtGpuTiming.elapsedNanos(0xfffa, 0xff02, 8, 2.0f));
    }

    @Test
    void handlesFullWidthWrapAndUnsignedDelta() {
        assertEquals(8.0, RtGpuTiming.elapsedNanos(-4, 4, 64, 1.0f));
        assertEquals(0x1.0p63, RtGpuTiming.elapsedNanos(0, Long.MIN_VALUE, 64, 1.0f));
    }
}
