package dev.comfyfluffy.caustica.renderer.runtime.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class RtJitterTest {
    @Test
    void firstSamplesAreCenteredHaltonCoordinates() {
        assertEquals(0, RtJitter.sample(0, 1280, 1920).x());
        assertEquals(-1.0f / 6, RtJitter.sample(0, 1280, 1920).y(), 1.0e-7f);
        assertEquals(-.25f, RtJitter.sample(1, 1280, 1920).x());
        assertEquals(1.0f / 6, RtJitter.sample(1, 1280, 1920).y(), 1.0e-7f);
    }

    @Test
    void samplingDoesNotAdvanceAnySequence() {
        var first = RtJitter.sample(0, 1280, 1920);
        assertNotEquals(first, RtJitter.sample(1, 1280, 1920));
        assertEquals(first, RtJitter.sample(0, 1280, 1920));
    }

    @Test
    void nativeAndQualityRenderingUseThirtyTwoPhases() {
        for (int renderWidth : new int[]{1920, 1280, 960}) {
            assertEquals(RtJitter.sample(0, renderWidth, 1920), RtJitter.sample(32, renderWidth, 1920));
            assertNotEquals(RtJitter.sample(0, renderWidth, 1920), RtJitter.sample(31, renderWidth, 1920));
        }
    }

    @Test
    void phaseCountGrowsWithUpscaleRatio() {
        assertEquals(RtJitter.sample(0, 640, 1920), RtJitter.sample(72, 640, 1920));
        assertNotEquals(RtJitter.sample(0, 640, 1920), RtJitter.sample(32, 640, 1920));
    }

    @Test
    void longSubmissionCountsRemainWithinTheRenderPixel() {
        var sample = RtJitter.sample(Long.MAX_VALUE, 1280, 1920);
        assertTrue(sample.x() >= -.5f && sample.x() < .5f);
        assertTrue(sample.y() >= -.5f && sample.y() < .5f);
        assertEquals(RtJitter.sample(31, 1280, 1920), sample);
    }
}
