package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.renderer.presentation.gen.NrdComposePushData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NrdComposePushDataTest {
    @Test
    void reflectedRootCarriesSixHeapIndicesExposureAndSignalEncoding() {
        ByteBuffer data = ByteBuffer.allocateDirect(NrdComposePushData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new NrdComposePushData(3, 5, 7, 11, 13, 17, 2.5f, 1).write(data);

        assertEquals(32, NrdComposePushData.BYTE_SIZE);
        assertEquals(3, data.getInt(0));
        assertEquals(5, data.getInt(4));
        assertEquals(7, data.getInt(8));
        assertEquals(11, data.getInt(12));
        assertEquals(13, data.getInt(16));
        assertEquals(17, data.getInt(20));
        assertEquals(2.5f, data.getFloat(24));
        assertEquals(1, data.getInt(28));
    }
}
