package dev.comfyfluffy.caustica.rt;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VrFoveationTest {
    private static final float EPSILON = 0.00001f;

    @Test
    void reportsTheDensityAtTheCenterOfTheAngularRegion() {
        int resolution = VrFoveation.insetResolution(2900, 3104);
        assertEquals(1344, resolution);
        assertEquals(65, VrFoveation.coverageDegrees(0));
        assertEquals(18, VrFoveation.pixelsPerDegree(resolution, 0));
        assertEquals(45, VrFoveation.coverageDegrees(4));
        assertEquals(28, VrFoveation.pixelsPerDegree(resolution, 4));
        assertEquals(25, VrFoveation.coverageDegrees(8));
        assertEquals(53, VrFoveation.pixelsPerDegree(resolution, 8));
        assertEquals(1000, VrFoveation.insetResolution(2100, 2000));
    }

    @Test
    void mapsTheSameHeadRaysToTheSameInsetForAsymmetricCantedEyes() {
        Matrix4f headView = new Matrix4f().rotationY(0.71f).rotateX(-0.24f).rotateZ(0.15f);
        Matrix4f inverseHeadView = new Matrix4f(headView).invert();
        float[][] directions = {{0f, 0f}, {-1f, -1f}, {-1f, 1f}, {1f, -1f}, {1f, 1f}};

        for (int eye : new int[]{-1, 1}) {
            Matrix4f projection = new Matrix4f().zero()
                    .m00(0.90f).m11(-1.05f)
                    .m20(eye * 0.18f).m21(eye * 0.08f)
                    .m23(-1f).m32(0.05f);
            Matrix4f eyeView = new Matrix4f().rotationY(eye * 0.07f).mul(headView);
            Matrix4f eyeViewProjection = projection.mul(eyeView, new Matrix4f());

            for (int detail : new int[]{0, 4, 8}) {
                float tangent = (float) Math.tan(Math.toRadians(VrFoveation.coverageDegrees(detail) * 0.5));
                Matrix4f insetViewProjection = VrFoveation.insetProjection(projection, detail).mul(headView);
                Matrix4f eyeToInset = new Matrix4f();
                VrFoveation.eyeToInset(eyeViewProjection, insetViewProjection, eyeToInset);

                for (float[] direction : directions) {
                    Vector4f ray = inverseHeadView.transform(new Vector4f(direction[0] * tangent,
                            direction[1] * tangent, -1f, 0f));
                    Vector4f eyeClip = eyeViewProjection.transform(ray);
                    Vector4f insetClip = eyeToInset.transform(new Vector4f(eyeClip.x / eyeClip.w,
                            eyeClip.y / eyeClip.w, 0f, 1f));
                    assertTrue(insetClip.w > 0f);
                    assertEquals(direction[0], insetClip.x / insetClip.w, EPSILON);
                    assertEquals(-direction[1], insetClip.y / insetClip.w, EPSILON);
                }
            }
        }
    }
}
