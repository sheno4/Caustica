package dev.comfyfluffy.caustica.renderer.presentation.fog;

import dev.comfyfluffy.caustica.renderer.presentation.gen.FogPushData;
import dev.comfyfluffy.caustica.renderer.presentation.gen.FogPushData.Float4;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FogInputsTest {
    @Test
    void spatialRevisionAndLightingRemainImmutable() {
        float[] cells = {1, 0.5f, 1, 64};
        FogField field = new FogField(0, 0, 0, 32, 1, 1, 1, cells);
        cells[0] = 4;
        field.voxels()[0] = 7;
        assertEquals(1, field.voxels()[0]);
        float[] light = {3, 4, 5};
        FogFrame frame = new FogFrame(field, 1, 64, 16, 0, light, light, light);
        light[0] = 6;
        frame.lightRadiance()[0] = 7;
        assertArrayEquals(new float[]{3, 4, 5}, frame.lightRadiance());
    }

    @Test
    void reflectedPushRecordPreservesAddressMatricesAndPadding() {
        var v = new Float4(1, 2, 3, 4);
        var record = new FogPushData(0x123456789abcdef0L, 0x1230L, 0x4560L, 1, 2, 3, 4, 5, 6, 0, 48,
                v, v, v, v, v, v, v, v, v, v, v, v);
        ByteBuffer buffer = ByteBuffer.allocate(FogPushData.BYTE_SIZE).order(ByteOrder.nativeOrder());
        for (int i = 0; i < buffer.capacity(); i++) buffer.put(i, (byte) 0xff);
        record.write(buffer);
        assertTrue(FogPushData.BYTE_SIZE <= 256);
        assertEquals(0x123456789abcdef0L, buffer.getLong(FogPushData.FIELD_ADDRESS_OFFSET));
        assertEquals(48, buffer.getInt(FogPushData.STEPS_OFFSET));
        assertEquals(0x1230L, buffer.getLong(FogPushData.VISIBILITY_RAYS_ADDRESS_OFFSET));
        assertEquals(0x4560L, buffer.getLong(FogPushData.VISIBILITY_RESULTS_ADDRESS_OFFSET));
        assertEquals(0, buffer.getLong(56));
        assertEquals(4, buffer.getFloat(FogPushData.CLIP_W_OFFSET + 12));
        assertEquals(3, buffer.getFloat(FogPushData.TLAS_CAMERA_OFFSET + 8));
    }
}
