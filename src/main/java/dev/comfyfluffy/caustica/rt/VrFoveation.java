package dev.comfyfluffy.caustica.rt;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Per-eye reconstruction sizing and the angular region shared by both eyes. */
public final class VrFoveation {
    public static final int MIN_WIDE_PERCENT = 25;
    public static final int MAX_WIDE_PERCENT = 100;
    public static final int MIN_INSET_RESOLUTION = 256;
    public static final int MAX_INSET_RESOLUTION = 4096;
    public static final int MIN_COVERAGE_DEGREES = 5;
    public static final int MAX_COVERAGE_DEGREES = 120;

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
        // Match the eye's clip-depth convention and create a symmetric angular window about head-forward.
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

    private static double halfAngleTangent(int coverageDegrees) {
        return Math.tan(Math.toRadians(coverageDegrees * 0.5));
    }
}
