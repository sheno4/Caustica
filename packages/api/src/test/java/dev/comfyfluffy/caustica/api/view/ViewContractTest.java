package dev.comfyfluffy.caustica.api.view;

import dev.comfyfluffy.caustica.api.scene.SceneId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class ViewContractTest {
    @Test
    void spatialMajorantMustBeFiniteAndNonnegative() {
        var type = dev.comfyfluffy.caustica.api.program.ShaderDataType.<Object>create("spatial");
        var volume = new dev.comfyfluffy.caustica.api.program.VolumeId<Object, Object>() { };
        for (float value : new float[]{-1, Float.NaN, Float.POSITIVE_INFINITY}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new SpatialMedium<>(volume, type.data(0), type.data(0), 0, 0, 0,
                            SpatialMedium.Transport.PATH_TRACED, value));
        }
        assertEquals(0, new SpatialMedium<>(volume, type.data(0), type.data(0)).extinctionMajorant());
    }

    @Test
    void sceneViewAssociatesCameraWithSelectedScene() {
        SceneId scene = new SceneId() { };
        SceneView view = new SceneView(scene, Camera.IDENTITY);

        assertSame(scene, view.entryScene());
        assertSame(Camera.IDENTITY, view.camera());
        assertSame(ViewMedium.Vacuum.INSTANCE, view.medium());
        org.junit.jupiter.api.Assertions.assertNull(view.spatialMedium());
    }

    @Test
    void viewCarriesTypedContainingVolumeIncludingZeroData() {
        var binding = dev.comfyfluffy.caustica.api.program.ShaderDataType.<Object>create("binding");
        var instance = dev.comfyfluffy.caustica.api.program.ShaderDataType.<Object>create("instance");
        var volume = new dev.comfyfluffy.caustica.api.program.VolumeId<Object, Object>() { };
        var medium = new ViewMedium.Volume<>(volume, binding.data(0L), instance.data(0L));

        assertSame(medium, new SceneView(new SceneId() { }, Camera.IDENTITY, medium).medium());
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
