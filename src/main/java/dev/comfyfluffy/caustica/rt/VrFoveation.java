package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.rt.gaze.PaperTrackerPacket;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Per-eye reconstruction sizing and the angular region used by foveated rendering. */
public final class VrFoveation {
    public static final int MIN_WIDE_PERCENT = 25;
    public static final int MAX_WIDE_PERCENT = 100;
    public static final int MIN_INSET_RESOLUTION = 256;
    public static final int MAX_INSET_RESOLUTION = 4096;
    public static final int MIN_COVERAGE_DEGREES = 5;
    public static final int MAX_COVERAGE_DEGREES = 120;

    public enum Mode {
        OFF("off"),
        FIXED("fixed"),
        PAPER_TRACKER("papertracker");

        private final String configValue;

        Mode(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }

        public boolean isEnabled() {
            return this != OFF;
        }

        public static Mode fromConfig(String value) {
            if ("fixed".equalsIgnoreCase(value)) {
                return FIXED;
            }
            if ("papertracker".equalsIgnoreCase(value)) {
                return PAPER_TRACKER;
            }
            return OFF;
        }
    }

    private VrFoveation() {}

    public static int wideResolution(int eyeDimension, float percent) {
        return (int) (eyeDimension * (double) percent / 100.0);
    }

    public static int insetResolution(int eyeWidth, int eyeHeight, int maxResolution) {
        return Math.min(maxResolution, Math.min(eyeWidth, eyeHeight));
    }

    public static int pixelsPerDegree(int resolution, int coverageDegrees) {
        // Rectilinear projection has its minimum angular density on the forward axis.
        return (int) Math.round(resolution * Math.toRadians(1.0) / (2.0 * halfAngleTangent(coverageDegrees)));
    }

    public static Matrix4f insetProjection(Matrix4fc eyeProjection, int coverageDegrees) {
        // Match the eye's clip-depth convention and create a symmetric angular window about the inset axis.
        float scale = Math.abs(eyeProjection.m23()) / (float) halfAngleTangent(coverageDegrees);
        return new Matrix4f().zero()
                .m00(Math.copySign(scale, eyeProjection.m00()))
                .m11(Math.copySign(scale, eyeProjection.m11()))
                .m22(eyeProjection.m22()).m23(eyeProjection.m23())
                .m32(eyeProjection.m32()).m33(eyeProjection.m33());
    }

    /** Maps eye clip coordinates onto the inset for the same ray from that eye. */
    public static void eyeToInset(Matrix4fc eyeViewProjection, Matrix4fc insetViewProjection, Matrix4f dest) {
        eyeViewProjection.invert(dest);
        insetViewProjection.mul(dest, dest);
    }

    /**
     * Converts head-centered calibration-plane angles (degrees) into a unit gaze direction in head space
     * (+X right, +Y up, -Z forward), accounting for per-eye parallax at 1.6 m.
     */
    public static Vector3f gazeHeadDirection(
            float yawDeg,
            float pitchDeg,
            boolean flipX,
            boolean flipY,
            float forwardYawDeg,
            float forwardPitchDeg,
            Vector3fc eyeOffsetInHeadMeters,
            Vector3f dest) {
        double sx = flipX ? -1.0 : 1.0;
        double sy = flipY ? -1.0 : 1.0;
        double d = PaperTrackerPacket.PLANE_DISTANCE_METERS;
        double px = d * (sx * Math.tan(Math.toRadians(yawDeg)) + Math.tan(Math.toRadians(forwardYawDeg)));
        double py = d * (sy * Math.tan(Math.toRadians(pitchDeg)) + Math.tan(Math.toRadians(forwardPitchDeg)));
        double pz = -d;

        float ox = eyeOffsetInHeadMeters != null ? eyeOffsetInHeadMeters.x() : 0f;
        float oy = eyeOffsetInHeadMeters != null ? eyeOffsetInHeadMeters.y() : 0f;
        float oz = eyeOffsetInHeadMeters != null ? eyeOffsetInHeadMeters.z() : 0f;

        float dx = (float) (px - ox);
        float dy = (float) (py - oy);
        float dz = (float) (pz - oz);
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || !Float.isFinite(dz) || dz >= -1.0e-4f) {
            return dest.set(0f, 0f, -1f);
        }
        return dest.set(dx, dy, dz).normalize();
    }

    /**
     * Clamps a head-space gaze direction to stay within {@code [-maxNdc, maxNdc]} of the given eye's
     * perspective frustum so the inset remains well inside the visible viewport.
     */
    public static Vector3f clampGazeToEyeFrustum(
            Matrix4fc eyeProjection,
            Matrix4fc eyeViewRotation,
            Matrix4fc headViewRotation,
            Vector3fc gazeDirHead,
            float maxNdc,
            Vector3f dest) {
        if (gazeDirHead == null || !Float.isFinite(gazeDirHead.x())
                || !Float.isFinite(gazeDirHead.y()) || !Float.isFinite(gazeDirHead.z())) {
            return dest.set(0f, 0f, -1f);
        }
        Vector3f dHead = new Vector3f(gazeDirHead);
        if (dHead.z >= -0.25f) {
            dHead.z = -0.25f;
        }
        if (dHead.lengthSquared() < 1.0e-8f) {
            return dest.set(0f, 0f, -1f);
        }
        dHead.normalize();

        Matrix4f headInv = new Matrix4f(headViewRotation).invert();
        Matrix4f eyeFromHead = new Matrix4f(eyeViewRotation).mul(headInv);
        Vector3f dEye = eyeFromHead.transformDirection(dHead, new Vector3f());

        float m00 = eyeProjection.m00();
        float m11 = eyeProjection.m11();
        float m20 = eyeProjection.m20();
        float m21 = eyeProjection.m21();
        float m23 = eyeProjection.m23();
        if (Math.abs(m00) < 1.0e-6f || Math.abs(m11) < 1.0e-6f || Math.abs(m23) < 1.0e-6f) {
            return dest.set(dHead);
        }

        float clipX = m00 * dEye.x + m20 * dEye.z;
        float clipY = m11 * dEye.y + m21 * dEye.z;
        float clipW = m23 * dEye.z;

        if (clipW <= 1.0e-4f) {
            Vector3f eyeForwardInHead = eyeFromHead.invert(new Matrix4f())
                    .transformDirection(new Vector3f(0f, 0f, -1f));
            return dest.set(eyeForwardInHead).normalize();
        }

        float ndcX = clipX / clipW;
        float ndcY = clipY / clipW;
        if (Math.abs(ndcX) <= maxNdc && Math.abs(ndcY) <= maxNdc) {
            return dest.set(dHead);
        }

        float clampedX = Math.clamp(ndcX, -maxNdc, maxNdc);
        float clampedY = Math.clamp(ndcY, -maxNdc, maxNdc);
        float zSign = m23 < 0f ? -1f : 1f;
        float positiveW = m23 * zSign;
        float eyeX = (clampedX * positiveW - m20 * zSign) / m00;
        float eyeY = (clampedY * positiveW - m21 * zSign) / m11;
        Vector3f clampedEye = new Vector3f(eyeX, eyeY, zSign).normalize();
        eyeFromHead.invert().transformDirection(clampedEye, dest);
        return dest.normalize();
    }

    /**
     * Builds a roll-free view-rotation matrix that points the inset's {@code -Z} axis along
     * {@code gazeDirHead} (expressed in head space).
     */
    public static Matrix4f insetViewRotation(Matrix4fc headViewRotation, Vector3fc gazeDirHead, Matrix4f dest) {
        if (gazeDirHead == null || !Float.isFinite(gazeDirHead.x())
                || !Float.isFinite(gazeDirHead.y()) || !Float.isFinite(gazeDirHead.z())
                || gazeDirHead.lengthSquared() < 1.0e-8f) {
            return dest.set(headViewRotation);
        }
        Vector3f gaze = new Vector3f(gazeDirHead).normalize();
        float backX = -gaze.x;
        float backY = -gaze.y;
        float backZ = -gaze.z;

        float rightX;
        float rightY = 0f;
        float rightZ;
        double horizLen = Math.hypot(gaze.x, gaze.z);
        if (horizLen < 1.0e-4) {
            rightX = 1f;
            rightZ = 0f;
        } else {
            rightX = (float) (-gaze.z / horizLen);
            rightZ = (float) (gaze.x / horizLen);
        }

        // up = back x right (with right.y == 0)
        float upX = backY * rightZ;
        float upY = backZ * rightX - backX * rightZ;
        float upZ = -backY * rightX;

        Matrix4f gazeRot = new Matrix4f().zero()
                .m00(rightX).m10(rightY).m20(rightZ)
                .m01(upX).m11(upY).m21(upZ)
                .m02(backX).m12(backY).m22(backZ)
                .m33(1f);
        return gazeRot.mul(headViewRotation, dest);
    }

    private static double halfAngleTangent(int coverageDegrees) {
        return Math.tan(Math.toRadians(coverageDegrees * 0.5));
    }
}
