package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.renderer.presentation.gen.PresentPushData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PresentPushDataTest {
    @Test
    void reflectedPresentationRootCarriesOnlyHeapIndicesAndPaperWhite() {
        ByteBuffer data = ByteBuffer.allocateDirect(PresentPushData.BYTE_SIZE).order(ByteOrder.nativeOrder());
        new PresentPushData(17, 23, 203.0f).write(data);

        assertEquals(12, PresentPushData.BYTE_SIZE);
        assertEquals(17, data.getInt(0));
        assertEquals(23, data.getInt(4));
        assertEquals(203.0f, data.getFloat(8));
    }
}
