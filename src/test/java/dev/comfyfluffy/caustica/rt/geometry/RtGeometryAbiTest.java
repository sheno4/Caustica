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
        assertEquals(24, RtGeometryAbi.PREVIOUS_POSITION_ADDRESS_OFFSET);
        assertEquals(32, RtGeometryAbi.VERTEX_NORMAL_ADDRESS_OFFSET);
        assertEquals(40, RtGeometryAbi.VERTEX_COLOR_ADDRESS_OFFSET);
        assertEquals(48, RtGeometryAbi.TRIANGLE_BASE_OFFSET);
        assertEquals(60, RtGeometryAbi.FLAGS_OFFSET);
        assertEquals(0, RtGeometryAbi.FLAG_TRIANGLE_CORNER_TEXTURE_COORDINATES);
        assertEquals(1, RtGeometryAbi.FLAG_INDEXED_TEXTURE_COORDINATES);
        assertEquals(2, RtGeometryAbi.FLAG_RECEIVES_PROJECTED_SURFACE_MODIFIERS);
        assertEquals(4, RtGeometryAbi.FLAG_HAS_VERTEX_NORMALS);
        assertEquals(8, RtGeometryAbi.FLAG_HAS_VERTEX_COLORS);
    }

    @Test
    void writesCanonicalGeometryRecordLayout() {
        ByteBuffer data = ByteBuffer.allocateDirect(RtGeometryAbi.RECORD_BYTES).order(ByteOrder.nativeOrder());
        RtGeometryAbi.writeRecord(MemoryUtil.memAddress(data), 1L, 2L, 3L, 4L, 5L, 6L,
                8, 9, 10, 11);

        assertEquals(1L, data.getLong(RtGeometryAbi.PRIMITIVE_ADDRESS_OFFSET));
        assertEquals(2L, data.getLong(RtGeometryAbi.INDEX_ADDRESS_OFFSET));
        assertEquals(3L, data.getLong(RtGeometryAbi.TEXTURE_COORDINATE_ADDRESS_OFFSET));
        assertEquals(4L, data.getLong(RtGeometryAbi.PREVIOUS_POSITION_ADDRESS_OFFSET));
        assertEquals(5L, data.getLong(RtGeometryAbi.VERTEX_NORMAL_ADDRESS_OFFSET));
        assertEquals(6L, data.getLong(RtGeometryAbi.VERTEX_COLOR_ADDRESS_OFFSET));
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
