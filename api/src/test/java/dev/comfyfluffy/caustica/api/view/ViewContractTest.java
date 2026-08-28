package dev.comfyfluffy.caustica.api.view;

import dev.comfyfluffy.caustica.api.scene.SceneId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class ViewContractTest {
    @Test
    void sceneViewAssociatesCameraWithSelectedScene() {
        SceneId scene = new SceneId() { };
        SceneView view = new SceneView(scene, Camera.IDENTITY);

        assertSame(scene, view.scene());
        assertSame(Camera.IDENTITY, view.camera());
    }

    @Test
    void cameraHasValueEqualityForMatrixContents() {
        Camera copy = new Camera(Camera.IDENTITY.x(), Camera.IDENTITY.y(), Camera.IDENTITY.z(),
                Camera.IDENTITY.clipFromView(), Camera.IDENTITY.viewFromSceneRotation());

        assertEquals(Camera.IDENTITY, copy);
        assertEquals(Camera.IDENTITY.hashCode(), copy.hashCode());
    }

    @Test
    void cameraRejectsNonRigidViewRotation() {
        float[] scaled = Camera.IDENTITY.viewFromSceneRotation();
        scaled[0] = 2.0f;

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Camera(0, 0, 0, Camera.IDENTITY.clipFromView(), scaled));
    }
}
