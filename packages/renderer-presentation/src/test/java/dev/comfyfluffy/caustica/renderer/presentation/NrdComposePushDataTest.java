package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.renderer.presentation.gen.NrdComposePushData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NrdComposePushDataTest {
    @Test
    void reflectedRootCarriesPlaneExchangeAddressesImagesAndMode() {
        ByteBuffer data = ByteBuffer.allocateDirect(NrdComposePushData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new NrdComposePushData(3L, 5L, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 2, 1, 0).write(data);

        assertEquals(72, NrdComposePushData.BYTE_SIZE);
        assertEquals(3L, data.getLong(0));
        assertEquals(5L, data.getLong(8));
        assertEquals(7, data.getInt(16));
        assertEquals(31, data.getInt(44));
        assertEquals(41, data.getInt(52));
        assertEquals(2, data.getInt(56));
        assertEquals(1, data.getInt(60));
        assertEquals(0, data.getInt(64));
    }
}
