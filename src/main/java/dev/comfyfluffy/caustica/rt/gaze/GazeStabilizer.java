package dev.comfyfluffy.caustica.rt.gaze;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.VrFoveation;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Tangent-plane gaze stabilizer converting PaperTracker samples into per-eye head-space directions.
 * Smooths micro-jitter during fixation, snaps immediately on saccades, and drifts back to head-forward
 * when tracking is lost.
 */
public final class GazeStabilizer {
    private static final double TWO_PI = 2.0 * Math.PI;
    private static final float DEGREES_PER_RADIAN = (float) (180.0 / Math.PI);
    private static final long SACCADE_HOLD_NS = 120_000_000L;
    private static final double LOST_DRIFT_TAU_MS = 300.0;
    private static final long TRANSITION_LOG_COOLDOWN_NS = 2_000_000_000L;

    public enum State {
        DISCONNECTED,
        TRACKING,
        SACCADE,
        STALE,
        LOST
    }

    public record Config(
            boolean combineGaze,
            boolean flipX,
            boolean flipY,
            float forwardYawDeg,
            float forwardPitchDeg,
            int staleMs,
            int lostMs,
            float smoothingHz,
            float deadbandDeg,
            float saccadeDegPerSec) {
    }

    public record EyeOutput(Vector3f headDirection, float jumpDegrees, boolean validSample) {
    }

    public record FrameOutput(State state, EyeOutput left, EyeOutput right, int sequence) {
    }

    private static final class EyeState {
        boolean tracked;
        float rawX;
        float rawY;
        float filteredX;
        float filteredY;
        float shownX;
        float shownY;
        float velX;
        float velY;
        long lastValidNanos;

        void reset() {
            tracked = false;
            rawX = 0f;
            rawY = 0f;
            filteredX = 0f;
            filteredY = 0f;
            shownX = 0f;
            shownY = 0f;
            velX = 0f;
            velY = 0f;
            lastValidNanos = 0L;
        }
    }

    private final LongSupplier clockNanos;
    private final EyeState[] eyes = {new EyeState(), new EyeState()};
    private boolean hasPacket;
    private int lastSequence;
    private long lastPacketNanos;
    private long lastUpdateNanos;
    private long saccadeUntilNanos;
    private long nextTransitionLogNanos;
    private State state = State.DISCONNECTED;

    public GazeStabilizer() {
        this(System::nanoTime);
    }

    public GazeStabilizer(LongSupplier clockNanos) {
        this.clockNanos = Objects.requireNonNull(clockNanos, "clockNanos");
    }

    public void reset() {
        eyes[0].reset();
        eyes[1].reset();
        hasPacket = false;
        lastSequence = 0;
        lastPacketNanos = 0L;
        lastUpdateNanos = 0L;
        saccadeUntilNanos = 0L;
        nextTransitionLogNanos = 0L;
        state = State.DISCONNECTED;
    }

    public State state() {
        return state;
    }

    public FrameOutput update(
            PaperTrackerPacket.Sample sample,
            Vector3fc leftEyeOffsetMeters,
            Vector3fc rightEyeOffsetMeters,
            Config config) {
        long now = clockNanos.getAsLong();
        double dtMs = lastUpdateNanos == 0L ? 0.0 : Math.clamp((now - lastUpdateNanos) / 1_000_000.0, 0.0, 250.0);
        lastUpdateNanos = now;

        boolean connected = sample != null;
        boolean fresh = false;
        float packetSeconds = 0f;
        if (connected) {
            fresh = !hasPacket || sample.sequence() != lastSequence;
            if (hasPacket && fresh && lastPacketNanos != 0L) {
                packetSeconds = (float) (Math.clamp((now - lastPacketNanos) / 1_000_000.0, 4.0, 250.0) / 1000.0);
            }
            lastSequence = sample.sequence();
            hasPacket = true;
            if (fresh) {
                lastPacketNanos = now;
            }
        } else {
            hasPacket = false;
            lastPacketNanos = 0L;
        }

        boolean silent = config.staleMs() > 0 && hasPacket
                && (now - lastPacketNanos) > config.staleMs() * 1_000_000L;

        boolean[] usable = new boolean[2];
        float[] measX = new float[2];
        float[] measY = new float[2];
        boolean snap = false;
        boolean saccadeDetected = false;

        if (connected && !silent) {
            boolean fused = config.combineGaze() && sample.fusedValid();
            boolean[] eyeFlagValid = {
                fused || sample.leftValid(),
                fused || sample.rightValid()
            };
            Vector3fc[] eyeOffsets = {leftEyeOffsetMeters, rightEyeOffsetMeters};
            Vector3f tmpDir = new Vector3f();
            long maxGapNanos = Math.max(config.staleMs(), 50) * 1_000_000L;

            for (int i = 0; i < 2; i++) {
                if (!eyeFlagValid[i]) {
                    continue;
                }
                float yaw = fused ? sample.fusedYawDeg() : (i == 0 ? sample.leftYawDeg() : sample.rightYawDeg());
                float pitch = fused ? sample.fusedPitchDeg() : (i == 0 ? sample.leftPitchDeg() : sample.rightPitchDeg());
                VrFoveation.gazeHeadDirection(yaw, pitch, config.flipX(), config.flipY(),
                        config.forwardYawDeg(), config.forwardPitchDeg(), eyeOffsets[i], tmpDir);
                if (tmpDir.z >= -1.0e-4f) {
                    continue;
                }
                usable[i] = true;
                measX[i] = tmpDir.x / -tmpDir.z;
                measY[i] = tmpDir.y / -tmpDir.z;

                EyeState e = eyes[i];
                if (!e.tracked) {
                    snap = true;
                } else if ((now - e.lastValidNanos) > maxGapNanos) {
                    snap = true;
                    if (angleDegrees(measX[i], measY[i], e.shownX, e.shownY) >= 1.0f) {
                        saccadeDetected = true;
                    }
                } else if (fresh && packetSeconds > 0f) {
                    float jump = angleDegrees(measX[i], measY[i], e.rawX, e.rawY);
                    float speed = jump / packetSeconds;
                    if (config.saccadeDegPerSec() > 0f && speed >= config.saccadeDegPerSec() && jump >= 1.0f) {
                        snap = true;
                        saccadeDetected = true;
                    }
                }
            }
        }

        if (saccadeDetected) {
            saccadeUntilNanos = now + SACCADE_HOLD_NS;
        }

        float deadbandTan = (float) Math.tan(Math.toRadians(Math.max(0f, config.deadbandDeg())));
        long lostNanos = Math.max(0, config.lostMs()) * 1_000_000L;
        EyeOutput[] outputs = new EyeOutput[2];

        for (int i = 0; i < 2; i++) {
            EyeState e = eyes[i];
            float prevShownX = e.shownX;
            float prevShownY = e.shownY;

            if (usable[i]) {
                float mx = measX[i];
                float my = measY[i];
                if (snap || !e.tracked || config.smoothingHz() <= 0f) {
                    e.filteredX = mx;
                    e.filteredY = my;
                    e.velX = 0f;
                    e.velY = 0f;
                } else if (fresh && packetSeconds > 0f) {
                    float velocityAlpha = (float) (1.0 - Math.exp(-TWO_PI * packetSeconds));
                    float instVx = (mx - e.rawX) / packetSeconds;
                    float instVy = (my - e.rawY) / packetSeconds;
                    e.velX += (instVx - e.velX) * velocityAlpha;
                    e.velY += (instVy - e.velY) * velocityAlpha;
                    float speedDeg = (float) Math.hypot(e.velX, e.velY) * DEGREES_PER_RADIAN;
                    float alpha = (float) (1.0 - Math.exp(
                            -TWO_PI * config.smoothingHz() * (1.0 + speedDeg / 30.0) * packetSeconds));
                    e.filteredX += (mx - e.filteredX) * alpha;
                    e.filteredY += (my - e.filteredY) * alpha;
                }
                e.rawX = mx;
                e.rawY = my;
                e.tracked = true;
                e.lastValidNanos = now;

                float dx = e.filteredX - e.shownX;
                float dy = e.filteredY - e.shownY;
                float dist = (float) Math.hypot(dx, dy);
                if (snap || deadbandTan <= 0f || dist > deadbandTan * 4.0f) {
                    e.shownX = e.filteredX;
                    e.shownY = e.filteredY;
                } else if (dist > deadbandTan) {
                    float pull = (dist - deadbandTan) / dist;
                    e.shownX += dx * pull;
                    e.shownY += dy * pull;
                }
            } else if (!e.tracked) {
                e.shownX = 0f;
                e.shownY = 0f;
            } else if ((now - e.lastValidNanos) >= lostNanos && dtMs > 0.0) {
                float pull = (float) (1.0 - Math.exp(-dtMs / LOST_DRIFT_TAU_MS));
                e.shownX += -e.shownX * pull;
                e.shownY += -e.shownY * pull;
            }

            float jumpDeg = angleDegrees(prevShownX, prevShownY, e.shownX, e.shownY);
            Vector3f dir = new Vector3f(e.shownX, e.shownY, -1.0f).normalize();
            outputs[i] = new EyeOutput(dir, jumpDeg, usable[i]);
        }

        State previous = state;
        long lastValid = Math.max(eyes[0].lastValidNanos, eyes[1].lastValidNanos);
        if (!connected && !eyes[0].tracked && !eyes[1].tracked) {
            state = State.DISCONNECTED;
        } else if (!usable[0] && !usable[1]) {
            if (lastValid == 0L) {
                state = connected ? State.LOST : State.DISCONNECTED;
            } else if ((now - lastValid) >= lostNanos) {
                state = State.LOST;
            } else {
                state = State.STALE;
            }
        } else if ((!usable[0] && eyes[0].tracked) || (!usable[1] && eyes[1].tracked)) {
            state = State.STALE;
        } else {
            state = now < saccadeUntilNanos ? State.SACCADE : State.TRACKING;
        }

        if (state != previous && now >= nextTransitionLogNanos) {
            nextTransitionLogNanos = now + TRANSITION_LOG_COOLDOWN_NS;
            CausticaMod.LOGGER.info("PaperTracker gaze state: {} -> {}", previous, state);
        }

        return new FrameOutput(state, outputs[0], outputs[1], connected ? sample.sequence() : 0);
    }

    static float angleDegrees(float ax, float ay, float bx, float by) {
        double dot = ax * (double) bx + ay * (double) by + 1.0;
        double norms = Math.sqrt((ax * (double) ax + ay * (double) ay + 1.0)
                * (bx * (double) bx + by * (double) by + 1.0));
        double cos = Math.clamp(dot / norms, -1.0, 1.0);
        return (float) Math.toDegrees(Math.acos(cos));
    }
}
