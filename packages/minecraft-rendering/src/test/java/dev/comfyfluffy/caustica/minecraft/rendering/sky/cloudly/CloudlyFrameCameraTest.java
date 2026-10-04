package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class CloudlyFrameCameraTest {
    @Test void sourceAxesAndSeaLevelUseTheHostScale() {
        float[] matrix = {1, 0, 0, 0, 0, -1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1};
        var camera = new CloudlyFrameCamera(matrix, 10, 100, 30, 64, 2);
        assertArrayEquals(new float[]{20, -60, 72}, camera.sourceOriginMeters());
        assertArrayEquals(new float[]{0, 1, 0}, camera.direction(0, 0));
        float[] topLeft = camera.direction(-1, -1);
        assertTrue(topLeft[0] < 0);
        assertTrue(topLeft[1] > 0);
        assertTrue(topLeft[2] > 0);
        assertEquals(Math.PI / 2, camera.verticalFieldOfView(), 1e-6);
    }

    @Test void framePayloadRetainsItsOwnInverseProjection() {
        float[] matrix = {1, 0, 0, 0, 0, -1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1};
        var camera = new CloudlyFrameCamera(matrix, 10, 100, 30, 64, 1);
        matrix[0] = 999;
        ByteBuffer payload = ByteBuffer.allocate(CloudlyFrameCamera.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        camera.write(payload);
        assertEquals(1, payload.getFloat(0));
        assertEquals(10, payload.getFloat(64));
        assertEquals(-30, payload.getFloat(68));
        assertEquals(36, payload.getFloat(72));
        assertEquals(1, payload.getFloat(76));
    }

    @Test void sourceTemporalMatricesProjectTheSameWorldPointAfterCameraMovement() {
        float[] inverseProjection = {1,0,0,0, 0,-1,0,0, 0,0,0,10, 0,0,-1,0};
        var previous = new CloudlyFrameCamera(inverseProjection, 0,64,0,64,2);
        var current = new CloudlyFrameCamera(inverseProjection, 2,64,0,64,2);
        float[] world = rowMultiply(new float[]{0,0,0.25f,1}, current.worldFromClipForSourceUniform());
        assertEquals(400, world[0] / world[3], 1e-4);
        assertEquals(80, world[1] / world[3], 1e-4);
        float[] projected = rowMultiply(world, current.worldToClipForSourceUniform());
        assertArrayEquals(new float[]{0,0,0.25f,1}, projected, 1e-5f);
        float[] reprojected = rowMultiply(new float[]{0,0,0.25f,1}, current.clipToPreviousForSourceUniform(previous));
        assertEquals(5, reprojected[0] / reprojected[3], 1e-5);
        assertEquals(0.25, reprojected[2] / reprojected[3], 1e-5);
        float[] depth = current.deviceDepthToViewCentimeters();
        assertEquals(80, 1 / (0.25f * depth[2] - depth[3]), 1e-4);
        float[] upper = rowMultiply(new float[]{0.1f,0.5f,0.25f,1}, current.worldFromClipForSourceUniform());
        assertArrayEquals(new float[]{408,80,40}, new float[]{upper[0]/upper[3],upper[1]/upper[3],upper[2]/upper[3]}, 1e-4f);
        assertArrayEquals(new float[]{40,20,80,1}, rowMultiply(new float[]{40,80,20,1}, current.viewFromTranslatedWorldForSourceUniform()), 1e-4f);
    }

    private static float[] rowMultiply(float[] vector, float[] columns) {
        float[] result = new float[4];
        for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++) {
            result[column] += vector[row] * columns[column * 4 + row];
        }
        return result;
    }
}
