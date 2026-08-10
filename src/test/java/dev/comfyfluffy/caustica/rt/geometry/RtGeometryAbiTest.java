package dev.comfyfluffy.caustica.rt.geometry;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtGeometryAbiTest {
    @Test
    void recordOffsetsAndSemanticFlagsRemainStable() {
        assertEquals(64, RtGeometryAbi.RECORD_BYTES);
        assertEquals(0, RtGeometryAbi.PRIMITIVE_ADDRESS_OFFSET);
        assertEquals(8, RtGeometryAbi.INDEX_ADDRESS_OFFSET);
        assertEquals(16, RtGeometryAbi.TEXTURE_COORDINATE_ADDRESS_OFFSET);
        assertEquals(24, RtGeometryAbi.MOTION_ADDRESS_OFFSET);
        assertEquals(32, RtGeometryAbi.RIGID_MOTION_OFFSET);
        assertEquals(48, RtGeometryAbi.TRIANGLE_BASE_OFFSET);
        assertEquals(60, RtGeometryAbi.FLAGS_OFFSET);
        assertEquals(0, RtGeometryAbi.FLAG_TRIANGLE_CORNER_TEXTURE_COORDINATES);
        assertEquals(1, RtGeometryAbi.FLAG_INDEXED_TEXTURE_COORDINATES);
    }

    @Test
    void writesCanonicalGeometryRecordLayout() {
        ByteBuffer data = ByteBuffer.allocateDirect(RtGeometryAbi.RECORD_BYTES).order(ByteOrder.nativeOrder());
        RtGeometryAbi.writeRecord(MemoryUtil.memAddress(data), 1L, 2L, 3L, 4L,
                5f, 6f, 7f, 8, 9, 10, 11);

        assertEquals(1L, data.getLong(RtGeometryAbi.PRIMITIVE_ADDRESS_OFFSET));
        assertEquals(2L, data.getLong(RtGeometryAbi.INDEX_ADDRESS_OFFSET));
        assertEquals(3L, data.getLong(RtGeometryAbi.TEXTURE_COORDINATE_ADDRESS_OFFSET));
        assertEquals(4L, data.getLong(RtGeometryAbi.MOTION_ADDRESS_OFFSET));
        assertEquals(5f, data.getFloat(RtGeometryAbi.RIGID_MOTION_OFFSET));
        assertEquals(6f, data.getFloat(RtGeometryAbi.RIGID_MOTION_OFFSET + 4));
        assertEquals(7f, data.getFloat(RtGeometryAbi.RIGID_MOTION_OFFSET + 8));
        assertEquals(0f, data.getFloat(RtGeometryAbi.RIGID_MOTION_OFFSET + 12));
        assertEquals(8, data.getInt(RtGeometryAbi.TRIANGLE_BASE_OFFSET));
        assertEquals(9, data.getInt(RtGeometryAbi.TRIANGLE_BASE_OFFSET + 4));
        assertEquals(10, data.getInt(RtGeometryAbi.TRIANGLE_BASE_OFFSET + 8));
        assertEquals(11, data.getInt(RtGeometryAbi.FLAGS_OFFSET));
    }

    @Test
    void unifiedIndexUsesAllTwentyFourCustomIndexBits() {
        assertEquals(RtGeometryAbi.MAX_RECORDS - 1,
                RtGeometryAbi.checkedIndex(RtGeometryAbi.MAX_RECORDS - 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> RtGeometryAbi.checkedIndex(RtGeometryAbi.MAX_RECORDS - 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> RtGeometryAbi.checkedRecordCount(RtGeometryAbi.MAX_RECORDS, 1));
    }
}
