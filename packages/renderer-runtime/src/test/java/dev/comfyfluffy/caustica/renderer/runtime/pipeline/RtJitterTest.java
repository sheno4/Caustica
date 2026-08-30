package dev.comfyfluffy.caustica.renderer.runtime.pipeline;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtJitterTest {
    @Test
    void rendererOwnsOneNonStaticJitterSequence() throws Exception {
        var jitterField = Class.forName("dev.comfyfluffy.caustica.renderer.runtime.RtFrameRenderer", false,
                        RtJitterTest.class.getClassLoader())
                .getDeclaredField("jitter");

        assertEquals(RtJitter.class, jitterField.getType());
        assertFalse(Modifier.isStatic(jitterField.getModifiers()));
        assertTrue(Modifier.isFinal(jitterField.getModifiers()));
        assertFalse(java.util.Arrays.stream(RtJitter.class.getDeclaredFields())
                .anyMatch(field -> Modifier.isStatic(field.getModifiers())
                        && field.getType() == RtJitter.class));
    }

    @Test
    void instancesAdvanceIndependently() {
        RtJitter first = new RtJitter();
        RtJitter second = new RtJitter();

        first.prepare(1280, 720, 1920);
        float firstPhaseX = first.jitterPixelsX();
        float firstPhaseY = first.jitterPixelsY();
        first.prepare(1280, 720, 1920);

        second.prepare(1280, 720, 1920);

        assertNotEquals(first.jitterPixelsX(), second.jitterPixelsX());
        assertNotEquals(first.jitterPixelsY(), second.jitterPixelsY());
        assertEquals(firstPhaseX, second.jitterPixelsX());
        assertEquals(firstPhaseY, second.jitterPixelsY());
    }

    @Test
    void newRendererJitterBeginsAtPhaseZero() {
        RtJitter advanced = new RtJitter();
        advanced.prepare(1280, 720, 1920);
        advanced.prepare(1280, 720, 1920);

        RtJitter fresh = new RtJitter();
        fresh.prepare(1280, 720, 1920);

        assertEquals(0.0f, fresh.jitterPixelsX());
        assertEquals(-1.0f / 6.0f, fresh.jitterPixelsY(), 1.0e-7f);
        assertNotEquals(advanced.jitterPixelsX(), fresh.jitterPixelsX());
        assertNotEquals(advanced.jitterPixelsY(), fresh.jitterPixelsY());
    }
}
