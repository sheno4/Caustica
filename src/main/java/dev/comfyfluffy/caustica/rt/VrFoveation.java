package dev.comfyfluffy.caustica.rt;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Fixed angular coverage shared by both eyes, with one reconstruction budget for all settings. */
public final class VrFoveation {
    public static final int MAX_DETAIL = 8;
    public static final int REFERENCE_RESOLUTION = 1344;

    private VrFoveation() {}

    public static int coverageDegrees(int detail) {
        return 65 - 5 * detail;
    }

    public static int insetResolution(int eyeWidth, int eyeHeight) {
        return Math.min(REFERENCE_RESOLUTION, Math.min(eyeWidth, eyeHeight) / 2);
    }

    public static int pixelsPerDegree(int resolution, int detail) {
        // Rectilinear projection has its minimum angular density on the forward axis.
        return (int) Math.round(resolution * Math.toRadians(1.0) / (2.0 * halfAngleTangent(detail)));
    }

    public static Matrix4f insetProjection(Matrix4fc eyeProjection, int detail) {
        // Match the eye's clip-depth convention and create a symmetric angular window about head-forward.
        float scale = Math.abs(eyeProjection.m23()) / (float) halfAngleTangent(detail);
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

    private static double halfAngleTangent(int detail) {
        return Math.tan(Math.toRadians(coverageDegrees(detail) * 0.5));
    }
}
