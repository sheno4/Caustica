package dev.comfyfluffy.caustica.rt;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Maps desktop hand geometry to the world trace while keeping Minecraft's hand framing. */
public final class RtViewModel {
    private RtViewModel() {}

    public static Matrix4f desktopPose(Matrix4fc projection, Matrix4fc rawProjection, Matrix4fc view,
                                       Matrix4fc handBob, float handFov, int width, int height) {
        if (width <= 0 || height <= 0 || !(handFov > 0f && handFov < 180f)) {
            return null;
        }
        float scale = (float) (1.0 / Math.tan(Math.toRadians(handFov) * 0.5));
        // Use the hand's angular projection with the world's depth planes. Matching depth planes
        // keeps the correction affine and preserves the scale of the submitted geometry.
        Matrix4f handProjection = new Matrix4f(rawProjection)
                .m00(Math.copySign(scale * height / width, rawProjection.m00()))
                .m11(Math.copySign(scale, rawProjection.m11())).m20(0f).m21(0f);
        Matrix4f pose = new Matrix4f(projection).mul(view).invert().mul(handProjection).mul(handBob);
        if (!pose.isFinite() || Math.abs(pose.m03()) > 0.001f || Math.abs(pose.m13()) > 0.001f
                || Math.abs(pose.m23()) > 0.001f || Math.abs(pose.m33() - 1f) > 0.001f) {
            return null;
        }
        return pose.m03(0f).m13(0f).m23(0f).m33(1f);
    }
}
