package dev.comfyfluffy.caustica.renderer.raytracing.accel;

import dev.comfyfluffy.caustica.renderer.raytracing.gen.OpacityMicromapPushData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class OpacityMicromapPushDataTest {
    @Test
    void reflectedPushDataMatchesTheShaderObjectAbi() {
        ByteBuffer bytes = ByteBuffer.allocate(OpacityMicromapPushData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new OpacityMicromapPushData(11L, 22L, 33L, 44L, 5, 6, 7).write(bytes);

        assertEquals(48, OpacityMicromapPushData.BYTE_SIZE);
        assertEquals(11L, bytes.getLong(0));
        assertEquals(44L, bytes.getLong(24));
        assertEquals(5, bytes.getInt(32));
        assertEquals(6, bytes.getInt(36));
        assertEquals(7, bytes.getInt(40));
        assertEquals(0, bytes.getInt(44));
    }
}
