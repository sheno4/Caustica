package dev.comfyfluffy.caustica.api.scene.geometry;

import dev.comfyfluffy.caustica.api.material.MaterialId;

import java.util.List;
import java.util.Objects;

/**
 * Triangle acceleration-structure input retained by the renderer.
 *
 * <p>Streams refer to source-owned Vulkan buffers. Their bytes remain unchanged until the batch which
 * introduced this build retires. The buffers have
 * {@code VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR} and
 * {@code VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT} usage.
 *
 * <p>The position ABI is deliberately fixed: each vertex begins with three little-endian IEEE-754
 * {@code float32} components at {@link Stream#byteOffset()}, in scene-local object coordinates. The
 * stride may include trailing source-owned attributes, but the acceleration build reads only those twelve
 * bytes. Indices are tightly packed little-endian unsigned 32-bit values.
 */
public record MeshBuild(Stream positions,
                        Stream previousPositions,
                        Stream indices,
                        int vertexCount,
                        UpdateIntent updateIntent,
                        TopologyRevision revision,
                        List<Geometry> geometries) {

    public MeshBuild {
        Objects.requireNonNull(positions, "positions");
        Objects.requireNonNull(indices, "indices");
        Objects.requireNonNull(updateIntent, "updateIntent");
        if (updateIntent == UpdateIntent.ALLOW_UPDATE) Objects.requireNonNull(revision, "revision");
        geometries = List.copyOf(geometries);
        if (vertexCount <= 0) throw new IllegalArgumentException("vertexCount must be positive");
        if (geometries.isEmpty()) throw new IllegalArgumentException("a mesh needs at least one geometry");
        if (positions.byteStride() < PositionFormat.FLOAT3.bytes()) {
            throw new IllegalArgumentException("position stride must contain a float3 position");
        }
        if (positions.byteSize() < requiredBytes(vertexCount, positions.byteStride(), PositionFormat.FLOAT3.bytes())) {
            throw new IllegalArgumentException("position stream is too small for vertexCount");
        }
        if (previousPositions != null) {
            if (previousPositions.byteStride() < PositionFormat.FLOAT3.bytes()) {
                throw new IllegalArgumentException("previous-position stride must contain a float3 position");
            }
            if (previousPositions.byteSize()
                    < requiredBytes(vertexCount, previousPositions.byteStride(), PositionFormat.FLOAT3.bytes())) {
                throw new IllegalArgumentException("previous-position stream is too small for vertexCount");
            }
        }
        if (indices.byteStride() != Integer.BYTES) {
            throw new IllegalArgumentException("indices must be tightly packed unsigned 32-bit integers");
        }

        long previousEnd = 0;
        for (Geometry geometry : geometries) {
            long end = Math.addExact((long) geometry.firstIndex(), geometry.indexCount());
            if (geometry.firstIndex() < previousEnd) {
                throw new IllegalArgumentException("geometry index slices must be ordered and disjoint");
            }
            if (end * Integer.BYTES > indices.byteSize()) {
                throw new IllegalArgumentException("geometry index slice exceeds the index stream");
            }
            previousEnd = end;
        }
    }

    private static long requiredBytes(int count, int stride, int elementBytes) {
        return Math.addExact(Math.multiplyExact((long) count - 1L, stride), elementBytes);
    }

    /** The only public position format in shader ABI version 1. */
    public enum PositionFormat {
        FLOAT3(12);

        private final int bytes;

        PositionFormat(int bytes) { this.bytes = bytes; }

        public int bytes() { return bytes; }
    }

    /** Whether the renderer may update a previous acceleration structure instead of rebuilding it. */
    public enum UpdateIntent {
        FORCE_REBUILD,
        ALLOW_UPDATE
    }

    /**
     * One strided stream inside a source-owned buffer.
     *
     * @param bufferDeviceAddress address of byte zero of the Vulkan buffer
     * @param byteOffset byte offset of the first element from {@code bufferDeviceAddress}
     * @param byteSize accessible byte count beginning at {@code byteOffset}
     * @param byteStride byte distance between elements
     */
    public record Stream(long bufferDeviceAddress, long byteOffset, long byteSize, int byteStride) {
        public Stream {
            if (bufferDeviceAddress == 0L) throw new IllegalArgumentException("bufferDeviceAddress must not be zero");
            if (byteOffset < 0L) throw new IllegalArgumentException("byteOffset must be non-negative");
            if (byteSize <= 0L) throw new IllegalArgumentException("byteSize must be positive");
            if (byteStride <= 0) throw new IllegalArgumentException("byteStride must be positive");
            if ((address(bufferDeviceAddress, byteOffset) & 3L) != 0L) {
                throw new IllegalArgumentException("the first stream element must be four-byte aligned");
            }
        }

        public long deviceAddress() { return address(bufferDeviceAddress, byteOffset); }

        private static long address(long base, long offset) {
            long address = base + offset;
            if (Long.compareUnsigned(address, base) < 0) {
                throw new IllegalArgumentException("stream address overflows uint64");
            }
            return address;
        }
    }

    /** Engine-recognized semantics that change traversal or engine-authored surface processing. */
    public enum GeometrySemantic {
        /** Every registered projected surface modifier runs for accepted hits on this geometry. */
        PROJECTED_SURFACE_MODIFIER_RECEIVER(0x1);

        private final int bit;

        GeometrySemantic(int bit) { this.bit = bit; }

        public int bit() { return bit; }
    }

    /** A compact shader-visible set of {@link GeometrySemantic} bits. */
    public record GeometrySemantics(int bits) {
        public static final GeometrySemantics NONE = new GeometrySemantics(0);
        public static final GeometrySemantics PROJECTED_SURFACE_MODIFIER_RECEIVER =
                new GeometrySemantics(GeometrySemantic.PROJECTED_SURFACE_MODIFIER_RECEIVER.bit());

        public GeometrySemantics {
            int known = 0;
            for (GeometrySemantic semantic : GeometrySemantic.values()) known |= semantic.bit();
            if ((bits & ~known) != 0) throw new IllegalArgumentException("unknown geometry semantic bits");
        }

        public boolean contains(GeometrySemantic semantic) {
            Objects.requireNonNull(semantic, "semantic");
            return (bits & semantic.bit()) != 0;
        }
    }

    /**
     * Optional renderer hint for building a four-state opacity micromap from the geometry's public
     * coverage implementation. Alpha at or below {@code transparentAlpha} is fully transparent; alpha at
     * or above {@code opaqueAlpha} is fully opaque; the interval remains unknown and executes any-hit.
     *
     * <p>The renderer may ignore this hint when opacity micromaps are unavailable or unprofitable. Coverage
     * evaluation therefore remains the required, behavior-defining fallback.
     */
    public record OpacityMicromapHint(float transparentAlpha, float opaqueAlpha, int subdivisionLevel) {
        public OpacityMicromapHint {
            if (!Float.isFinite(transparentAlpha) || !Float.isFinite(opaqueAlpha)
                    || transparentAlpha < 0.0f || opaqueAlpha > 1.0f
                    || transparentAlpha > opaqueAlpha) {
                throw new IllegalArgumentException("opacity thresholds must be finite, ordered, and in [0,1]");
            }
            if (subdivisionLevel < 0 || subdivisionLevel > 12) {
                throw new IllegalArgumentException("subdivisionLevel must be in [0,12]");
            }
        }
    }

    /** One material's contiguous triangle slice. */
    public record Geometry(MaterialId material, int firstIndex, int indexCount, long attributes,
                           boolean opaque, GeometrySemantics semantics, OpacityMicromapHint opacityMicromap) {
        public Geometry {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(semantics, "semantics");
            if (firstIndex < 0 || firstIndex % 3 != 0) {
                throw new IllegalArgumentException("firstIndex must be a non-negative triangle boundary");
            }
            if (indexCount <= 0 || indexCount % 3 != 0) {
                throw new IllegalArgumentException("indexCount must contain complete triangles");
            }
            if (opaque && opacityMicromap != null) {
                throw new IllegalArgumentException("opaque geometry cannot need an opacity micromap");
            }
        }

        public int triangleCount() { return indexCount / 3; }
    }

    /**
     * Source-owned topology identity. Reusing a non-null revision asserts that vertex count, index bytes, ordered
     * geometry slices, position format and stride, semantic flags, and opacity-micromap topology are
     * unchanged. Only position bytes and uninterpreted shading words may differ. Breaking this assertion
     * is a contract violation the renderer cannot detect. {@code FORCE_REBUILD} may use a null revision;
     * {@code ALLOW_UPDATE} requires one.
     */
    public record TopologyRevision(long value) { }
}
