package dev.comfyfluffy.caustica.renderer.raytracing;

import dev.comfyfluffy.caustica.renderer.raytracing.gen.NrdPlaneFrameData;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NrdPlaneFrameDataTest {
    @Test
    void writesNonJitteredCameraRelativePlaneProjectionState() {
        ByteBuffer data = ByteBuffer.allocateDirect(NrdPlaneFrameData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        Matrix4f current = new Matrix4f().m00(2.0f);
        Matrix4f previous = new Matrix4f().m11(3.0f);
        new NrdPlaneFrameData(current, previous,
                new NrdPlaneFrameData.Float3(5.0f, 7.0f, 11.0f), 13.0f,
                new NrdPlaneFrameData.Float3(17.0f, 19.0f, 23.0f), RtNrdComposePipeline.RADIANCE_SCALE,
                1920, 1080).write(data);

        assertEquals(176, NrdPlaneFrameData.BYTE_SIZE);
        assertEquals(2.0f, data.getFloat(0));
        assertEquals(3.0f, data.getFloat(64 + 20));
        assertEquals(5.0f, data.getFloat(128));
        assertEquals(13.0f, data.getFloat(140));
        assertEquals(17.0f, data.getFloat(144));
        assertEquals(RtNrdComposePipeline.RADIANCE_SCALE, data.getFloat(156));
        assertEquals(1920, data.getInt(160));
        assertEquals(1080, data.getInt(164));
    }
}
