package dev.comfyfluffy.caustica.rt.gaze;

import dev.comfyfluffy.caustica.compat.VivecraftCompat.View;
import org.joml.Vector3fc;

/**
 * Coordinates the PaperTracker UDP receiver and gaze stabilizer across Vivecraft's per-eye passes,
 * latching one stabilized sample per stereo frame so both eyes observe the same gaze packet.
 */
public final class PaperTrackerGazeClient implements AutoCloseable {
    private final PaperTrackerUdpReceiver receiver;
    private final GazeStabilizer stabilizer;

    private GazeStabilizer.FrameOutput latchedFrame;
    private boolean consumedByRight;

    public PaperTrackerGazeClient() {
        this(new PaperTrackerUdpReceiver(), new GazeStabilizer());
    }

    PaperTrackerGazeClient(PaperTrackerUdpReceiver receiver, GazeStabilizer stabilizer) {
        this.receiver = receiver;
        this.stabilizer = stabilizer;
    }

    /**
     * Resolves the stabilized gaze output for the active VR eye, polling and advancing once per stereo
     * frame (on {@link View#LEFT}, or when no unconsumed frame is latched).
     */
    public GazeStabilizer.FrameOutput resolveForEye(
            View eye,
            Vector3fc leftEyeOffsetMeters,
            Vector3fc rightEyeOffsetMeters,
            int port,
            GazeStabilizer.Config config) {
        if (!eye.isEye()) {
            throw new IllegalArgumentException("Gaze resolution requires a VR eye view, got " + eye);
        }
        if (eye == View.LEFT || latchedFrame == null || consumedByRight) {
            PaperTrackerPacket.Sample sample = receiver.poll(port);
            latchedFrame = stabilizer.update(sample, leftEyeOffsetMeters, rightEyeOffsetMeters, config);
            consumedByRight = false;
        }
        if (eye == View.RIGHT) {
            consumedByRight = true;
        }
        return latchedFrame;
    }

    public GazeStabilizer.State state() {
        return latchedFrame != null ? latchedFrame.state() : GazeStabilizer.State.DISCONNECTED;
    }

    public void deactivate() {
        receiver.close();
        stabilizer.reset();
        latchedFrame = null;
        consumedByRight = false;
    }

    @Override
    public void close() {
        deactivate();
    }
}
