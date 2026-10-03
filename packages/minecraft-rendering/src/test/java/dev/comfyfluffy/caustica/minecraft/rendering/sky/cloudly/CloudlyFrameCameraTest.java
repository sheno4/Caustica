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
}
