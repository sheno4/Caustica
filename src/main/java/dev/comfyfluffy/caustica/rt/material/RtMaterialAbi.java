package dev.comfyfluffy.caustica.rt.material;

/** Shared CPU invariants for per-primitive material records. */
public final class RtMaterialAbi {
    private RtMaterialAbi() {
    }

    /** One primitive record occupies three float4 lanes. */
    public static final int PRIMITIVE_RECORD_FLOATS = 12;
    public static final int PRIMITIVE_RECORD_BYTES = PRIMITIVE_RECORD_FLOATS * Float.BYTES;

    public static int checkedPrimitiveCount(int materialFloatCount) {
        if (materialFloatCount < 0 || materialFloatCount % PRIMITIVE_RECORD_FLOATS != 0) {
            throw new IllegalArgumentException("primitive record buffer has " + materialFloatCount
                    + " floats; expected a multiple of " + PRIMITIVE_RECORD_FLOATS);
        }
        return materialFloatCount / PRIMITIVE_RECORD_FLOATS;
    }

    public static void requireTriangleParity(int materialFloatCount, int indexCount) {
        if (indexCount < 0 || indexCount % 3 != 0) {
            throw new IllegalArgumentException("triangle index buffer has " + indexCount
                    + " entries; expected complete triangles");
        }
        int primitives = checkedPrimitiveCount(materialFloatCount);
        int triangles = indexCount / 3;
        if (primitives != triangles) {
            throw new IllegalArgumentException("primitive record/index mismatch: " + primitives
                    + " primitive records for " + triangles + " triangles");
        }
    }
}
