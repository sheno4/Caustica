package dev.comfyfluffy.caustica.rt;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class RtViewModelTest {
    @Test
    void preservesHandFramingThroughTheBobbingWorldCamera() {
        Matrix4f world = new Matrix4f().zero().m00(0.8f).m11(-1.6f).m23(-1f).m32(0.05f);
        Matrix4f view = new Matrix4f().rotationY(0.73f).rotateX(-0.24f);
        Matrix4f bob = new Matrix4f().rotationZ(-0.04f).translate(0.04f, -0.02f, 0f);
        Matrix4f levelProjection = new Matrix4f(world).mul(bob).rotateY(0.1f);
        Matrix4f corrected = RtViewModel.desktopPose(levelProjection, world, view, bob, 70f, 1920, 1080);
        assertNotNull(corrected);

        Vector4f vertex = new Vector4f(0.24f, -0.31f, -0.84f, 1f);
        Vector4f projected = new Matrix4f(levelProjection).mul(view).mul(corrected).transform(new Vector4f(vertex));
        Vector4f vanilla = bob.transform(vertex);
        float focalLength = (float) (1.0 / Math.tan(Math.toRadians(35.0)));
        assertEquals(focalLength * (1080f / 1920f) * vanilla.x / -vanilla.z,
                projected.x / projected.w, 0.00001f);
        assertEquals(-focalLength * vanilla.y / -vanilla.z, projected.y / projected.w, 0.00001f);
        assertEquals(0.05f / -vanilla.z, projected.z / projected.w, 0.00001f);
    }
}
