package dev.comfyfluffy.caustica.engine.frame;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import dev.comfyfluffy.caustica.api.view.ViewMedium;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameSnapshotTest {
    @Test
    void permitsZeroForTypedNonAddressVolumeData() {
        ShaderDataType<Object> bindingType = ShaderDataType.create("binding");
        ShaderDataType<Object> instanceType = ShaderDataType.create("instance");
        VolumeId<Object, Object> volume = new VolumeId<>() {};

        assertDoesNotThrow(() -> new ViewMedium.Volume<>(
                volume, bindingType.data(0L), instanceType.data(0L)));
    }

    @Test
    void ownsMatrixCopies() {
        Matrix4f projection = new Matrix4f().perspective(1.0f, 1.5f, 0.1f, 1000.0f);
        Matrix4f view = new Matrix4f().rotateY(0.4f);
        SceneOrigin origin = new SceneOrigin(16, 32, 48);
        ShaderDataType<Object> bindingType = ShaderDataType.create("binding");
        ShaderDataType<Object> instanceType = ShaderDataType.create("instance");
        VolumeId<Object, Object> volume = new VolumeId<>() {};
        SceneId scene = new SceneId() {};
        ViewMedium.Volume<Object, Object> medium = new ViewMedium.Volume<>(
                volume, bindingType.data(11L), instanceType.data(12L));
        FrameSnapshot snapshot = new FrameSnapshot(new SceneView(scene,
                new Camera(1.0, 2.0, 3.0, projection.get(new float[16]), view.get(new float[16])), medium), origin,
                true, 4.0, 1.0);

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
        assertEquals(scene, snapshot.view().entryScene());
        assertEquals(11L, medium.bindingData().bits());
        assertEquals(12L, medium.instanceData().bits());
        assertEquals(origin, snapshot.sceneOrigin());
        assertTrue(snapshot.proceduralSurfaceAnimationEnabled());
    }
}
