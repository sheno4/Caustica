package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import java.util.List;

/** Immutable, section-local source geometry produced by Minecraft terrain extraction. */
public record MinecraftTerrainMesh(float[] positions, int[] indices, float[] cornerUvs,
                                   float[] primitiveData, List<Geometry> geometries,
                                   long indexRevision) {
    public static final int PRIMITIVE_FLOATS = 12;
    static final int PRIMITIVE_ATLAS_PRESENT_OFFSET = 9;
    static final int PRIMITIVE_TRANSMISSION_ALPHA_OFFSET = 10;

    public MinecraftTerrainMesh {
        positions = positions.clone();
        indices = indices.clone();
        cornerUvs = cornerUvs.clone();
        primitiveData = primitiveData.clone();
        geometries = List.copyOf(geometries);
        if (positions.length == 0 || positions.length % 3 != 0) {
            throw new IllegalArgumentException("positions must contain float3 vertices");
        }
        if (indices.length == 0 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("indices must contain complete triangles");
        }
        int triangleCount = indices.length / 3;
        if (cornerUvs.length != triangleCount * 6) {
            throw new IllegalArgumentException("corner UVs must contain three float2 values per triangle");
        }
        if (primitiveData.length != triangleCount * PRIMITIVE_FLOATS) {
            throw new IllegalArgumentException("primitive data must contain twelve floats per triangle");
        }
        requireFinite(positions, "positions");
        requireFinite(cornerUvs, "corner UVs");
        requireFinite(primitiveData, "primitive data");
        int vertexCount = positions.length / 3;
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("index exceeds the vertex stream");
            }
        }
        int nextIndex = 0;
        for (Geometry geometry : geometries) {
            if (geometry.firstIndex() != nextIndex) {
                throw new IllegalArgumentException("geometry slices must cover the index stream in order");
            }
            nextIndex = Math.addExact(nextIndex, geometry.indexCount());
        }
        if (nextIndex != indices.length) {
            throw new IllegalArgumentException("geometry slices must cover the complete index stream");
        }
    }

    @Override public float[] positions() { return positions.clone(); }
    @Override public int[] indices() { return indices.clone(); }
    @Override public float[] cornerUvs() { return cornerUvs.clone(); }
    @Override public float[] primitiveData() { return primitiveData.clone(); }

    public int vertexCount() { return positions.length / 3; }
    public int triangleCount() { return indices.length / 3; }

    private static void requireFinite(float[] values, String name) {
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
        }
    }

    /** Shader implementation selected for a contiguous triangle range. */
    public enum ProgramCategory { MATERIAL, WATER, PORTAL }

    /** Traversal category remains separate from optical transmission. */
    public enum Coverage { OPAQUE, CUTOUT, STOCHASTIC }

    /**
     * One shader-homogeneous range in the source index stream. A shadow blocker certificate applies to
     * every primitive in the range and requires a resolved built-in material with zero transmission
     * throughout its resource-pack epoch, full coverage, and no interior volume.
     */
    public record Geometry(ProgramCategory program, Coverage coverage, int firstIndex, int indexCount,
                           float alphaCutoff, dev.comfyfluffy.caustica.api.geometry.OpacityMicromap opacityMicromap,
                           boolean guaranteedShadowBlocker) {
        public Geometry(ProgramCategory program, Coverage coverage, int firstIndex, int indexCount, float alphaCutoff) {
            this(program, coverage, firstIndex, indexCount, alphaCutoff, null, false);
        }
        public Geometry(ProgramCategory program, Coverage coverage, int firstIndex, int indexCount, float alphaCutoff,
                        dev.comfyfluffy.caustica.api.geometry.OpacityMicromap opacityMicromap) {
            this(program, coverage, firstIndex, indexCount, alphaCutoff, opacityMicromap, false);
        }
        public Geometry {
            if (guaranteedShadowBlocker && (program != ProgramCategory.MATERIAL || coverage != Coverage.OPAQUE)) {
                throw new IllegalArgumentException("terrain shadow certificates require a fully covered material surface");
            }
            java.util.Objects.requireNonNull(program, "program");
            java.util.Objects.requireNonNull(coverage, "coverage");
            if (firstIndex < 0 || firstIndex % 3 != 0 || indexCount <= 0 || indexCount % 3 != 0) {
                throw new IllegalArgumentException("geometry range must contain complete triangles");
            }
            if (!Float.isFinite(alphaCutoff) || alphaCutoff < 0f || alphaCutoff > 1f) {
                throw new IllegalArgumentException("alphaCutoff must be in [0,1]");
            }
        }
    }
}
