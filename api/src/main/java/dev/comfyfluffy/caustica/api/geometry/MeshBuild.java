package dev.comfyfluffy.caustica.api.geometry;

import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeId;

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
 * {@code float32} components at {@link Stream#byteOffset()}, in mesh-local coordinates. The
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

    /** The only public position format in the current shader ABI. */
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
     * @param bufferDeviceAddress {@code VkDeviceAddress} of byte zero of the Vulkan buffer; LWJGL represents
     *                            this non-handle 64-bit Vulkan value as {@code long}
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

    /**
     * The visible surface slot of a geometry and the traversal policy of that surface. A volume-only
     * boundary has no surface slot, so none of these surface-only values exist for it.
     *
     * <p>{@code alphaCutoff} is renderer-visible traversal policy rather than a renderer-owned material.
     * The opacity micromap remains an optional acceleration of the surface's required coverage program.
     * {@code opaque} means coverage is uniformly one and traversal may skip any-hit; it says nothing about
     * the surface's optical transmission.
     */
    public record SurfaceSlot(SurfaceId surface, float alphaCutoff, boolean opaque,
                              OpacityMicromapHint opacityMicromap) {
        public SurfaceSlot {
            Objects.requireNonNull(surface, "surface");
            if (!Float.isFinite(alphaCutoff) || alphaCutoff < 0.0f || alphaCutoff > 1.0f) {
                throw new IllegalArgumentException("alphaCutoff must be in [0,1]");
            }
            if (opaque && opacityMicromap != null) {
                throw new IllegalArgumentException("opaque geometry cannot need an opacity micromap");
            }
        }
    }

    /**
     * One contiguous triangle slice carrying independent surface and interior-volume slots.
     *
     * <p>{@code surface} may be {@code null} for an invisible volume boundary; {@code volume} may be
     * {@code null} for ordinary surface geometry. At least one slot is present. A volume slot describes the
     * interior entered through the outward-facing side of this triangle slice. Every slice bounding one
     * interior uses the same volume, and its mesh is closed, manifold, and outward-oriented. These topology
     * properties are assertions by the source because the renderer cannot validate source-owned buffers.
     * Surface coverage affects visible boundary shading, not whether a volume crossing exists; model a real
     * opening with boundary geometry that has no volume slot. A surface paired with a volume returns
     * {@code geometry_thin_walled = false}; a thin sheet does not enclose an interior.
     *
     * <p>{@code data} reaches the selected surface, coverage, and volume programs unchanged. It commonly
     * addresses an extension-owned geometry record containing primitive attributes and indices into the
     * extension's own shading tables.
     */
    public record Geometry(SurfaceSlot surface, VolumeId volume,
                           int firstIndex, int indexCount, long data) {
        public Geometry {
            if (surface == null && volume == null) {
                throw new IllegalArgumentException("geometry needs a surface or volume slot");
            }
            if (firstIndex < 0 || firstIndex % 3 != 0) {
                throw new IllegalArgumentException("firstIndex must be a non-negative triangle boundary");
            }
            if (indexCount <= 0 || indexCount % 3 != 0) {
                throw new IllegalArgumentException("indexCount must contain complete triangles");
            }
        }

        public int triangleCount() { return indexCount / 3; }
    }

    /**
     * Source-owned topology identity. Reusing a non-null revision asserts that vertex count, index bytes, ordered
     * geometry slices, position format and stride, selected surface and volume slots, coverage policy, and
     * opacity-micromap topology are unchanged. Only position bytes and uninterpreted data words may differ.
     * Breaking this assertion
     * is a contract violation the renderer cannot detect. {@code FORCE_REBUILD} may use a null revision;
     * {@code ALLOW_UPDATE} requires one.
     */
    public record TopologyRevision(long value) { }
}
