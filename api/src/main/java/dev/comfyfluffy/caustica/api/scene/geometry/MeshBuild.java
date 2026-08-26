package dev.comfyfluffy.caustica.api.scene.geometry;

import dev.comfyfluffy.caustica.api.material.MaterialId;

import java.util.List;
import java.util.Objects;

/**
 * A mesh, described as what an acceleration-structure build consumes and nothing else.
 *
 * <p>Every stream is a device address into a buffer the source owns and keeps unchanged until the mesh
 * retires. Positions and indices need
 * {@code VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR} and
 * {@code VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT}.
 *
 * <p>There are no normals, texture coordinates, colours, or per-triangle shading fields here, because a
 * build reads none of them. Everything a surface needs travels as {@link Geometry#attributes}, which the
 * renderer copies into its per-geometry record without looking at it.
 */
public record MeshBuild(Stream positions,
                        Stream previousPositions,
                        Stream indices,
                        int vertexCount,
                        TopologyRevision revision,
                        List<Geometry> geometries) {

    public MeshBuild {
        Objects.requireNonNull(positions, "positions");
        Objects.requireNonNull(indices, "indices");
        geometries = List.copyOf(geometries);
        if (vertexCount <= 0) {
            throw new IllegalArgumentException("vertexCount must be positive");
        }
        if (geometries.isEmpty()) {
            throw new IllegalArgumentException("a mesh needs at least one geometry");
        }
        if (indices.byteStride() != 4) {
            throw new IllegalArgumentException("indices must be tightly packed unsigned 32-bit integers");
        }
    }

    /** One strided stream inside a source-owned buffer, addressed by its first byte. */
    public record Stream(long deviceAddress, long byteSize, int byteStride) {
        public Stream {
            if (deviceAddress == 0L) throw new IllegalArgumentException("deviceAddress must not be zero");
            if (byteSize <= 0L) throw new IllegalArgumentException("byteSize must be positive");
            if (byteStride <= 0) throw new IllegalArgumentException("byteStride must be positive");
            if ((deviceAddress & 3L) != 0L) {
                throw new IllegalArgumentException("mesh streams must be four-byte aligned");
            }
        }
    }

    /**
     * One material's contiguous slice of {@link MeshBuild#indices}, which becomes one geometry in the
     * acceleration structure.
     *
     * <p>Material is per geometry because that is the granularity Vulkan already selects at —
     * {@code instanceShaderBindingTableRecordOffset} plus {@code geometryIndex} picks the hit record. A
     * source sorts its triangles by material when it builds; only the index buffer is grouped, never the
     * vertices.
     *
     * <p>{@code attributes} is an uninterpreted 64-bit word, normally a device address pointing at
     * <em>this geometry's</em> attribute slice. Per-geometry rather than per-mesh on purpose:
     * {@code primitiveIndex} in a hit shader is geometry-local and restarts at zero for each geometry, so
     * pointing it here makes {@code attributes[primitiveIndex]} correct with no base to add.
     *
     * <p>{@code opaque} becomes {@code VK_GEOMETRY_OPAQUE_BIT_KHR}: true skips any-hit entirely, so a
     * geometry whose coverage shader can reject a hit must pass false.
     */
    public record Geometry(MaterialId material, int firstIndex, int indexCount,
                           long attributes, boolean opaque) {
        public Geometry {
            Objects.requireNonNull(material, "material");
            if (firstIndex < 0 || firstIndex % 3 != 0) {
                throw new IllegalArgumentException("firstIndex must be a non-negative triangle boundary");
            }
            if (indexCount <= 0 || indexCount % 3 != 0) {
                throw new IllegalArgumentException("indexCount must contain complete triangles");
            }
        }

        public int triangleCount() {
            return indexCount / 3;
        }
    }

    /**
     * Source-owned identity of vertex correspondence, index order, and geometry layout. Submitting a mesh
     * with an unchanged revision lets the renderer keep the acceleration structure it already built and
     * re-read only what changed. Null means no reuse.
     *
     * <p>Reusing a value for different topology is a contract violation the renderer cannot detect.
     */
    public record TopologyRevision(long value) { }
}
