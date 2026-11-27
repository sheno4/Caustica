package dev.comfyfluffy.caustica.rt.gaze;

import java.util.concurrent.atomic.AtomicLong;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GazeStabilizerTest {
    private static final Vector3f LEFT_EYE = new Vector3f(-0.032f, 0f, 0f);
    private static final Vector3f RIGHT_EYE = new Vector3f(0.032f, 0f, 0f);

    private static GazeStabilizer.Config defaultConfig() {
        return new GazeStabilizer.Config(true, false, false, 0f, 0f, 300, 1000, 8f, 0.8f, 120f);
    }

    private static PaperTrackerPacket.Sample fusedSample(int seq, float yaw, float pitch) {
        return new PaperTrackerPacket.Sample(seq,
                PaperTrackerPacket.FLAG_LEFT_VALID | PaperTrackerPacket.FLAG_RIGHT_VALID
                        | PaperTrackerPacket.FLAG_FUSED_VALID,
                100, yaw, pitch, yaw, pitch, yaw, pitch);
    }

    @Test
    void computesParallaxAndCalibrationOffsets() {
        AtomicLong clockNs = new AtomicLong(1_000_000_000L);
        GazeStabilizer stabilizer = new GazeStabilizer(clockNs::get);
        GazeStabilizer.Config cfg = new GazeStabilizer.Config(
                true, true, false, 5.0f, -2.0f, 300, 1000, 0f, 0f, 120f);

        GazeStabilizer.FrameOutput out = stabilizer.update(fusedSample(1, 5.0f, -2.0f), LEFT_EYE, RIGHT_EYE, cfg);
        assertEquals(GazeStabilizer.State.TRACKING, out.state());
        // flipX flips +5 deg to -5 deg, cancelling forwardYaw = +5 deg -> head X = 0, so left/right differ by IPD.
        assertTrue(out.left().headDirection().x > 0f);
        assertTrue(out.right().headDirection().x < 0f);
        assertTrue(out.left().headDirection().y < 0f);
    }

    @Test
    void holdsInsideDeadbandAndSnapsOnSaccade() {
        AtomicLong clockNs = new AtomicLong(1_000_000_000L);
        GazeStabilizer stabilizer = new GazeStabilizer(clockNs::get);
        GazeStabilizer.Config cfg = defaultConfig();

        GazeStabilizer.FrameOutput initial = stabilizer.update(fusedSample(1, 0f, 0f), LEFT_EYE, RIGHT_EYE, cfg);
        assertEquals(GazeStabilizer.State.TRACKING, initial.state());
        Vector3f baseLeft = new Vector3f(initial.left().headDirection());

        // Small micro-jitter (0.2 deg) inside 0.8 deg deadband should hold shown direction steady.
        clockNs.addAndGet(11_000_000L);
        GazeStabilizer.FrameOutput jitter = stabilizer.update(fusedSample(2, 0.2f, 0f), LEFT_EYE, RIGHT_EYE, cfg);
        assertEquals(baseLeft.x, jitter.left().headDirection().x, 1.0e-5f);
        assertEquals(baseLeft.y, jitter.left().headDirection().y, 1.0e-5f);

        // Rapid 25-degree saccade in 11ms (> 120 deg/s) triggers immediate snap and SACCADE state.
        clockNs.addAndGet(11_000_000L);
        GazeStabilizer.FrameOutput saccade = stabilizer.update(fusedSample(3, 25f, 0f), LEFT_EYE, RIGHT_EYE, cfg);
        assertEquals(GazeStabilizer.State.SACCADE, saccade.state());
        assertTrue(saccade.left().jumpDegrees() > 20f);
        assertTrue(saccade.left().headDirection().x > 0.35f);
    }

    @Test
    void transitionsThroughStaleAndLostAndDriftsToForward() {
        AtomicLong clockNs = new AtomicLong(1_000_000_000L);
        GazeStabilizer stabilizer = new GazeStabilizer(clockNs::get);
        GazeStabilizer.Config cfg = defaultConfig();

        stabilizer.update(fusedSample(1, 20f, 10f), LEFT_EYE, RIGHT_EYE, cfg);

        // Sequence stops advancing for 350ms -> STALE, holds position.
        clockNs.addAndGet(350_000_000L);
        GazeStabilizer.FrameOutput stale = stabilizer.update(fusedSample(1, 20f, 10f), LEFT_EYE, RIGHT_EYE, cfg);
        assertEquals(GazeStabilizer.State.STALE, stale.state());
        assertTrue(stale.left().headDirection().x > 0.25f);
        assertFalse(stale.left().validSample());

        // Advance past lostMs (1000ms since last valid sample at t=0; currently at 350ms -> +700ms = 1050ms).
        clockNs.addAndGet(700_000_000L);
        float prevX = stale.left().headDirection().x;
        for (int i = 0; i < 8; i++) {
            GazeStabilizer.FrameOutput lost = stabilizer.update(null, LEFT_EYE, RIGHT_EYE, cfg);
            assertEquals(GazeStabilizer.State.LOST, lost.state());
            assertTrue(lost.left().headDirection().x < prevX);
            prevX = lost.left().headDirection().x;
            clockNs.addAndGet(200_000_000L);
        }
        assertTrue(prevX < 0.05f);
    }

    @Test
    void staleZeroKeepsStationaryValidSampleAlive() {
        AtomicLong clockNs = new AtomicLong(1_000_000_000L);
        GazeStabilizer stabilizer = new GazeStabilizer(clockNs::get);
        GazeStabilizer.Config cfg = new GazeStabilizer.Config(
                true, false, false, 0f, 0f, 0, 1000, 8f, 0.8f, 120f);

        stabilizer.update(fusedSample(1, 15f, 0f), LEFT_EYE, RIGHT_EYE, cfg);
        clockNs.addAndGet(5_000_000_000L);
        GazeStabilizer.FrameOutput stillValid = stabilizer.update(fusedSample(1, 15f, 0f), LEFT_EYE, RIGHT_EYE, cfg);
        assertEquals(GazeStabilizer.State.TRACKING, stillValid.state());
        assertTrue(stillValid.left().validSample());
    }
}
