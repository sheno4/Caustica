package dev.comfyfluffy.caustica.rt.geometry;

import org.lwjgl.system.MemoryUtil;

/** CPU-side layout and bounds for the geometry records read by the world hit shaders. */
public final class RtGeometryAbi {
    public static final int RECORD_BYTES = 64;
    public static final int MAX_RECORDS = 1 << 24;

    public static final int PRIMITIVE_ADDRESS_OFFSET = 0;
    public static final int INDEX_ADDRESS_OFFSET = 8;
    public static final int TEXTURE_COORDINATE_ADDRESS_OFFSET = 16;
    public static final int MOTION_ADDRESS_OFFSET = 24;
    public static final int RIGID_MOTION_OFFSET = 32;
    public static final int TRIANGLE_BASE_OFFSET = 48;
    public static final int FLAGS_OFFSET = 60;

    public static final int FLAG_TRIANGLE_CORNER_TEXTURE_COORDINATES = 0;
    public static final int FLAG_INDEXED_TEXTURE_COORDINATES = 1;
    public static final int FLAG_RECEIVES_PROJECTED_SURFACE_MODIFIERS = 1 << 1;

    private RtGeometryAbi() {
    }

    public static int checkedRecordCount(int retainedRecords, int dynamicCapacity) {
        int count = Math.addExact(retainedRecords, dynamicCapacity);
        if (retainedRecords < 0 || dynamicCapacity < 0 || count > MAX_RECORDS) {
            throw new IllegalArgumentException("geometry table needs " + count
                    + " records; instanceCustomIndex supports at most " + MAX_RECORDS);
        }
        return count;
    }

    public static int checkedIndex(int retainedRecords, int dynamicIndex) {
        int index = Math.addExact(retainedRecords, dynamicIndex);
        if (retainedRecords < 0 || dynamicIndex < 0 || index >= MAX_RECORDS) {
            throw new IllegalArgumentException("geometry table index is outside instanceCustomIndex: " + index);
        }
        return index;
    }

    public static void writeRecord(long address, long primitiveAddress, long indexAddress,
                                   long textureCoordinateAddress, long motionAddress,
                                   float rigidX, float rigidY, float rigidZ,
                                   int triangleBase0, int triangleBase1, int triangleBase2, int flags) {
        MemoryUtil.memPutLong(address + PRIMITIVE_ADDRESS_OFFSET, primitiveAddress);
        MemoryUtil.memPutLong(address + INDEX_ADDRESS_OFFSET, indexAddress);
        MemoryUtil.memPutLong(address + TEXTURE_COORDINATE_ADDRESS_OFFSET, textureCoordinateAddress);
        MemoryUtil.memPutLong(address + MOTION_ADDRESS_OFFSET, motionAddress);
        MemoryUtil.memPutFloat(address + RIGID_MOTION_OFFSET, rigidX);
        MemoryUtil.memPutFloat(address + RIGID_MOTION_OFFSET + 4, rigidY);
        MemoryUtil.memPutFloat(address + RIGID_MOTION_OFFSET + 8, rigidZ);
        MemoryUtil.memPutFloat(address + RIGID_MOTION_OFFSET + 12, 0f);
        MemoryUtil.memPutInt(address + TRIANGLE_BASE_OFFSET, triangleBase0);
        MemoryUtil.memPutInt(address + TRIANGLE_BASE_OFFSET + 4, triangleBase1);
        MemoryUtil.memPutInt(address + TRIANGLE_BASE_OFFSET + 8, triangleBase2);
        MemoryUtil.memPutInt(address + FLAGS_OFFSET, flags);
    }

    /** Host-visible retained table prefix copied into a frame table before dynamic records are appended. */
    public record TablePrefix(long mappedAddress, int recordCount) {
        public TablePrefix {
            if (mappedAddress == 0L) {
                throw new IllegalArgumentException("geometry table prefix has no mapped address");
            }
            checkedRecordCount(recordCount, 0);
        }
    }
}
