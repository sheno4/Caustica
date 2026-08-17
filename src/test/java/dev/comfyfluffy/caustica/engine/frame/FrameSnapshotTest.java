package dev.comfyfluffy.caustica.engine.frame;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameSnapshotTest {
    @Test
    void ownsMatrixCopies() {
        Matrix4f projection = new Matrix4f().perspective(1.0f, 1.5f, 0.1f, 1000.0f);
        Matrix4f view = new Matrix4f().rotateY(0.4f);
        SceneOrigin origin = new SceneOrigin(16, 32, 48);
        FrameSnapshot snapshot = new FrameSnapshot(projection, view, 1.0, 2.0, 3.0, origin,
                new FrameSnapshot.CameraMedium(new MaterialHandle(ResourceId.of("test", "medium")),
                        new FrameSnapshot.LinearRgb(0.1f, 0.2f, 0.3f)), true, 4.0, 1.0, 7L);

        float capturedProjectionM00 = snapshot.copyProjection().m00();
        float capturedViewM00 = snapshot.copyViewRotation().m00();
        projection.identity();
        view.identity();

        Matrix4f firstProjectionCopy = snapshot.copyProjection();
        Matrix4f secondProjectionCopy = snapshot.copyProjection();
        firstProjectionCopy.identity();
        assertNotSame(firstProjectionCopy, secondProjectionCopy);
        assertEquals(capturedProjectionM00, secondProjectionCopy.m00());
        assertEquals(capturedViewM00, snapshot.copyViewRotation().m00());
        assertEquals(ResourceId.of("test", "medium"), snapshot.cameraMedium().material().id());
        assertEquals(new FrameSnapshot.LinearRgb(0.1f, 0.2f, 0.3f),
                snapshot.cameraMedium().sourceColor());
        assertEquals(origin, snapshot.sceneOrigin());
        assertTrue(snapshot.proceduralSurfaceAnimationEnabled());
    }
}
