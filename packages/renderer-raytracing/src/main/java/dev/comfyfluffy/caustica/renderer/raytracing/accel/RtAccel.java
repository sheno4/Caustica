package dev.comfyfluffy.caustica.renderer.raytracing.accel;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkAccelerationStructureTrianglesOpacityMicromapEXT;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCopyAccelerationStructureInfoKHR;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkMicromapBuildInfoEXT;
import org.lwjgl.vulkan.VkMicromapBuildSizesInfoEXT;
import org.lwjgl.vulkan.VkMicromapCreateInfoEXT;
import org.lwjgl.vulkan.VkMicromapTriangleEXT;
import org.lwjgl.vulkan.VkMicromapUsageEXT;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor.GraphicsUse;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor.TrackedGraphicsUse;

import java.util.List;

import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_ACCESS_2_MICROMAP_READ_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_ACCESS_2_MICROMAP_WRITE_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_BUILD_MICROMAP_MODE_BUILD_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_PIPELINE_STAGE_2_MICROMAP_BUILD_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.vkCmdBuildMicromapsEXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.vkCreateMicromapEXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.vkDestroyMicromapEXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.vkGetMicromapBuildSizesEXT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DATA_ACCESS_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_INDEX_TYPE_NONE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdCopyAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdWriteAccelerationStructuresPropertiesKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCreateAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkDestroyAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;

/**
 * A built acceleration structure plus its backing buffer. BLAS factories and lifetime operations remain
 * here; {@link TlasBuilder} owns frame-level TLAS preparation and reuse.
 */
public final class RtAccel {
    // vkCmdBuildMicromapsEXT requires both data.deviceAddress and triangleArray.deviceAddress to be
    // multiples of 256 (VUID-vkCmdBuildMicromapsEXT-pInfos-07515).
    private static final long MICROMAP_INPUT_ADDRESS_ALIGNMENT = 256L;

    private static GpuBuffer createScratchBuffer(VulkanDeviceContext ctx, long requiredSize, String label) {
        long alignment = ctx.accelerationStructureScratchAlignment();
        return ctx.createAlignedBuffer(Math.max(requiredSize, alignment), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                false, label, alignment);
    }

    private static long scratchAddress(VulkanDeviceContext ctx, GpuBuffer scratch) {
        long alignment = ctx.accelerationStructureScratchAlignment();
        if ((scratch.deviceAddress() & (alignment - 1L)) != 0L) {
            throw new IllegalStateException("Scratch device address 0x"
                    + Long.toUnsignedString(scratch.deviceAddress(), 16) + " is not aligned to " + alignment);
        }
        return scratch.deviceAddress();
    }

    public final long handle;
    public final long deviceAddress;

    private final GpuBuffer backing;
    private final boolean ownsBacking;
    private OpacityMicromap opacityMicromap;
    private long compactionQueryPool;
    private final VkDevice vk;
    private boolean destroyed;

    RtAccel(VkDevice vk, long handle, long deviceAddress, GpuBuffer backing) {
        this(vk, handle, deviceAddress, backing, true);
    }

    private RtAccel(VkDevice vk, long handle, long deviceAddress, GpuBuffer backing, boolean ownsBacking) {
        this(vk, handle, deviceAddress, backing, ownsBacking, null);
    }

    private RtAccel(VkDevice vk, long handle, long deviceAddress, GpuBuffer backing, boolean ownsBacking,
                    OpacityMicromap opacityMicromap) {
        this.vk = vk;
        this.handle = handle;
        this.deviceAddress = deviceAddress;
        this.backing = backing;
        this.ownsBacking = ownsBacking;
        this.opacityMicromap = opacityMicromap;
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        if (handle != 0L) {
            vkDestroyAccelerationStructureKHR(vk, handle, null);
        }
        if (opacityMicromap != null) {
            opacityMicromap.destroy();
            opacityMicromap = null;
        }
        if (compactionQueryPool != 0L) {
            VK10.vkDestroyQueryPool(vk, compactionQueryPool, null);
            compactionQueryPool = 0L;
        }
        // Caller-owned backing is released separately from the acceleration-structure handle.
        if (ownsBacking) {
            backing.destroy();
        }
        destroyed = true;
    }

    private OpacityMicromap detachOpacityMicromap() {
        OpacityMicromap result = opacityMicromap;
        opacityMicromap = null;
        return result;
    }

    /** CPU-generated opacity micromap input for one retained geometry's triangle order. */
    public record OpacityMicromapInput(byte[] data, byte[] triangles, int triangleCount, int subdivisionLevel,
                                       int bytesPerTriangle) {
    }

    @FunctionalInterface
    public interface OpacityMicromapEncoder {
        void record(VkCommandBuffer command, long dataAddress, long triangleAddress, int dataStride);
    }

    /** Device-generated opacity input. One encoder invocation owns each triangle's padded data block. */
    public record OpacityMicromapGpuInput(int triangleCount, int subdivisionLevel, int bytesPerTriangle,
                                          OpacityMicromapEncoder encoder) {
        public OpacityMicromapGpuInput {
            if (triangleCount <= 0 || subdivisionLevel < 0 || subdivisionLevel > 4 || bytesPerTriangle <= 0) {
                throw new IllegalArgumentException("invalid GPU opacity micromap input");
            }
            java.util.Objects.requireNonNull(encoder, "encoder");
            if ((long) triangleCount * ((bytesPerTriangle + 3) & -4) > 0xFFFF_FFFFL) {
                throw new IllegalArgumentException("opacity micromap data offsets exceed uint32");
            }
        }

        int dataStride() {
            return (bytesPerTriangle + 3) & -4;
        }
    }

    /** Pack {@code VkMicromapTriangleEXT[]} records into plain bytes so workers can prepare them off-thread. */
    public static byte[] opacityMicromapTriangles(int triangleCount, int subdivisionLevel, int bytesPerTriangle) {
        byte[] triangles = new byte[triangleCount * VkMicromapTriangleEXT.SIZEOF];
        for (int t = 0; t < triangleCount; t++) {
            int base = t * VkMicromapTriangleEXT.SIZEOF;
            putLe32(triangles, base, t * bytesPerTriangle);
            putLe16(triangles, base + 4, subdivisionLevel);
            putLe16(triangles, base + 6, VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
        }
        return triangles;
    }

    private static void putLe32(byte[] dst, int offset, int value) {
        dst[offset] = (byte) value;
        dst[offset + 1] = (byte) (value >>> 8);
        dst[offset + 2] = (byte) (value >>> 16);
        dst[offset + 3] = (byte) (value >>> 24);
    }

    private static void putLe16(byte[] dst, int offset, int value) {
        dst[offset] = (byte) value;
        dst[offset + 1] = (byte) (value >>> 8);
    }

    private static final class OpacityMicromap {
        final VkDevice vk;
        final long handle;
        final GpuBuffer backing;
        GpuBuffer data;
        GpuBuffer triangles;
        GpuBuffer scratch;
        final long scratchAddress;
        final long dataAddress;
        final long triangleArrayAddress;
        final int triangleCount;
        final int subdivisionLevel;
        final int bytesPerTriangle;
        final OpacityMicromapEncoder encoder;
        final int dataStride;
        boolean destroyed;

        OpacityMicromap(VkDevice vk, long handle, GpuBuffer backing, GpuBuffer data, GpuBuffer triangles,
                        GpuBuffer scratch, long scratchAddress, long dataAddress, long triangleArrayAddress, int triangleCount,
                        int subdivisionLevel, int bytesPerTriangle, OpacityMicromapEncoder encoder, int dataStride) {
            this.vk = vk;
            this.handle = handle;
            this.backing = backing;
            this.data = data;
            this.triangles = triangles;
            this.scratch = scratch;
            this.scratchAddress = scratchAddress;
            this.dataAddress = dataAddress;
            this.triangleArrayAddress = triangleArrayAddress;
            this.triangleCount = triangleCount;
            this.subdivisionLevel = subdivisionLevel;
            this.bytesPerTriangle = bytesPerTriangle;
            this.encoder = encoder;
            this.dataStride = dataStride;
        }

        void freeBuildInputs() {
            if (scratch != null) {
                scratch.destroy();
                scratch = null;
            }
            if (triangles != null) {
                triangles.destroy();
                triangles = null;
            }
            if (data != null) {
                data.destroy();
                data = null;
            }
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            if (handle != 0L) {
                vkDestroyMicromapEXT(vk, handle, null);
            }
            freeBuildInputs();
            backing.destroy();
            destroyed = true;
        }
    }

    /**
     * A BLAS whose AS + backing buffer are allocated but whose build command is recorded later, so
     * many retained builds can be batched into one queue submission and fence
     * wait per batch instead of one per geometry (each submit drains the graphics queue).
     * {@code opaque} marks geometry {@code OPAQUE} (solid, no any-hit) vs
     * {@code NO_DUPLICATE_ANY_HIT_INVOCATION} for alpha-tested cutout.
     */
    public static final class PreparedBlas {
        public final RtAccel accel;
        private final GpuBuffer scratch;
        // Non-null only when the AS backing buffer is caller-owned, so releaseTransientBlas destroys it
        // explicitly rather than accel.destroy() doing so.
        private final GpuBuffer externalBacking;
        private final long vertexAddr;
        private final int vertexStride;
        private final long indexAddr;
        private final int maxVertex;
        private final int triangleCount;
        private final boolean opaque;
        private final String label;
        // UPDATE support. {@code updatable} = built with ALLOW_UPDATE; {@code update} selects MODE_UPDATE.
        private final boolean updatable;
        private final boolean update;
        private final RtAccel updateSource;
        // PREFER_FAST_BUILD instead of PREFER_FAST_TRACE. Only meaningful alongside externalClassSplit;
        // every other path leaves this false (PREFER_FAST_TRACE).
        private final boolean fastBuild;
        // Retained packed multi-geometry split: one geometry per SBT class, in the fixed packed
        // order { opaque, masked, transmissive } (see SBT_CLASSES). Class 0 (opaque) is flagged
        // VK_GEOMETRY_OPAQUE_BIT. The fixed geometry indices are also SBT class indices: radiance rays use
        // closest-hit-only records for opaque/transmissive and an any-hit record for masked; shadow rays
        // use any-hit records for masked/transmissive.
        // With neither split present, one geometry range covers the complete triangle stream.
        private final boolean retainedSplit;
        private final int[] retainedClassTriangles; // per-class triangle counts in SBT_CLASSES order (null if !retainedSplit)
        private final boolean externalClassSplit;
        private final int[] externalClassTriangles;
        private final List<GeometryRange> geometryRanges;
        private final OpacityMicromap opacityMicromap; // optional, retained masked class only

        private PreparedBlas(RtAccel accel, GpuBuffer scratch, GpuBuffer externalBacking, long vertexAddr, long indexAddr,
                             int maxVertex, int triangleCount, boolean opaque, String label, boolean updatable, boolean update) {
            this(accel, scratch, externalBacking, vertexAddr, indexAddr, maxVertex, triangleCount, opaque, label,
                    updatable, update, null, false, false, null, false, null, null);
        }

        private PreparedBlas(RtAccel accel, GpuBuffer scratch, GpuBuffer externalBacking, long vertexAddr, long indexAddr,
                             int maxVertex, int triangleCount, boolean opaque, String label, boolean updatable, boolean update,
                             RtAccel updateSource, boolean fastBuild, boolean retainedSplit, int[] retainedClassTriangles,
                             boolean externalClassSplit, int[] externalClassTriangles,
                             OpacityMicromap opacityMicromap) {
            this.accel = accel;
            this.scratch = scratch;
            this.externalBacking = externalBacking;
            this.vertexAddr = vertexAddr;
            this.vertexStride = 3 * Float.BYTES;
            this.indexAddr = indexAddr;
            this.maxVertex = maxVertex;
            this.triangleCount = triangleCount;
            this.opaque = opaque;
            this.label = label;
            this.updatable = updatable;
            this.update = update;
            this.updateSource = updateSource;
            this.fastBuild = fastBuild;
            this.retainedSplit = retainedSplit;
            this.retainedClassTriangles = retainedClassTriangles;
            this.externalClassSplit = externalClassSplit;
            this.externalClassTriangles = externalClassTriangles;
            this.geometryRanges = null;
            this.opacityMicromap = opacityMicromap;
        }

        private PreparedBlas(RtAccel accel, GpuBuffer scratch, GpuBuffer externalBacking,
                             long vertexAddr, long indexAddr, int maxVertex,
                             int vertexStride, List<GeometryRange> geometryRanges, String label) {
            this.accel = accel;
            this.scratch = scratch;
            this.externalBacking = externalBacking;
            this.vertexAddr = vertexAddr;
            this.vertexStride = vertexStride;
            this.indexAddr = indexAddr;
            this.maxVertex = maxVertex;
            this.triangleCount = geometryRanges.stream().mapToInt(GeometryRange::triangleCount).sum();
            this.opaque = false;
            this.label = label;
            this.updatable = false;
            this.update = false;
            this.updateSource = null;
            this.fastBuild = false;
            this.retainedSplit = false;
            this.retainedClassTriangles = null;
            this.externalClassSplit = false;
            this.externalClassTriangles = null;
            this.geometryRanges = List.copyOf(geometryRanges);
            this.opacityMicromap = null;
        }

        /** A retained packed BLAS split into fixed per-class geometries in {@link RtAccel#SBT_CLASSES} order. */
        static PreparedBlas retained(RtAccel accel, GpuBuffer scratch, GpuBuffer externalBacking, long vertexAddr, long indexAddr, int maxVertex,
                                    int[] retainedClassTriangles, OpacityMicromap opacityMicromap, String label) {
            int total = 0;
            for (int t : retainedClassTriangles) {
                total += t;
            }
            return new PreparedBlas(accel, scratch, externalBacking, vertexAddr, indexAddr, maxVertex,
                    total, false, label, false, false, null, false, true, retainedClassTriangles, false, null, opacityMicromap);
        }

        static PreparedBlas externalClassified(RtAccel accel, GpuBuffer scratch, GpuBuffer externalBacking,
                                               long vertexAddr, long indexAddr, int maxVertex,
                                               int[] classTriangles, String label,
                                               boolean updatable, boolean update) {
            return externalClassified(accel, scratch, externalBacking, vertexAddr, indexAddr, maxVertex,
                    classTriangles, label, updatable, update, false);
        }

        static PreparedBlas externalClassified(RtAccel accel, GpuBuffer scratch, GpuBuffer externalBacking,
                                               long vertexAddr, long indexAddr, int maxVertex,
                                               int[] classTriangles, String label,
                                               boolean updatable, boolean update, boolean fastBuild) {
            return externalClassified(accel, scratch, externalBacking, vertexAddr, indexAddr, maxVertex,
                    classTriangles, label, updatable, update, null, fastBuild);
        }

        static PreparedBlas externalClassified(RtAccel accel, GpuBuffer scratch, GpuBuffer externalBacking,
                                               long vertexAddr, long indexAddr, int maxVertex,
                                               int[] classTriangles, String label,
                                               boolean updatable, boolean update, RtAccel updateSource,
                                               boolean fastBuild) {
            int total = 0;
            for (int triangles : classTriangles) total += triangles;
            return new PreparedBlas(accel, scratch, externalBacking, vertexAddr, indexAddr, maxVertex,
                    total, false, label, updatable, update, updateSource, fastBuild, false, null, true, classTriangles, null);
        }

        public boolean requestsCompaction() {
            return accel.compactionQueryPool != 0L;
        }

        /** Whether command recording selects acceleration-structure UPDATE rather than BUILD mode. */
        public boolean updateMode() {
            return update;
        }

        /** Caller-owned backing retained with a prepared persistent candidate, if any. */
        public GpuBuffer externalBacking() {
            return externalBacking;
        }

        private void freeTransientBuildResources() {
            scratch.destroy();
            if (opacityMicromap != null) {
                opacityMicromap.freeBuildInputs();
            }
        }
    }

    // SBT hit-group classes, shared by retained and transient geometry alike. Geometry indices are fixed and
    // double as SBT material record indices. Masked and masked-transmissive surfaces share one record because
    // shadow any-hit reads the binding's transmissive flag, so these three classes cover every reachable
    // combination of coverage and transmittance.
    public static final int CLASS_OPAQUE = 0;       // no any-hit either ray type
    public static final int CLASS_MASKED = 1;       // any-hit both ray types (cutout/stochastic coverage)
    public static final int CLASS_TRANSMISSIVE = 2; // any-hit shadow only (opaque coverage, transmissive)
    public static final int SBT_CLASSES = 3;
    public static final int SBT_RAY_RADIANCE = 0;
    public static final int SBT_RAY_SHADOW = 1;
    public static final int SBT_RADIANCE_OFFSET = SBT_RAY_RADIANCE * SBT_CLASSES; // 0
    public static final int SBT_SHADOW_OFFSET = SBT_RAY_SHADOW * SBT_CLASSES;     // 3
    public static final int SBT_HIT_GROUP_COUNT = SBT_CLASSES * 2;                // 6

    /**
     * Result of {@link #prepareUpdatableBlasBuild}: the per-frame BUILD op to record, plus the persistent
     * resources the caller's persistent slot must keep ({@code backing}) and cache ({@code updateScratchSize}
     * for sizing later refit scratch). The {@code scratch} is this frame's transient build scratch (release
     * at the frames-in-flight horizon, like the mesh buffers); the {@code op.accel} + {@code backing} persist.
     */
    public record UpdatableBuild(PreparedBlas op, RtAccel accel, GpuBuffer backing, GpuBuffer scratch, long updateScratchSize) {
    }

    /**
     * Initial BUILD for a caller-owned persistent BLAS that will never be updated in place. The caller
     * retains {@code accel} + {@code backing}, retires {@code scratch} after the build completes, and later
     * destroys the pair with {@link #destroyCallerOwnedAccel}. This avoids ALLOW_UPDATE overhead for
     * immutable cached geometry.
     */
    public record PersistentBuild(PreparedBlas op, RtAccel accel, GpuBuffer backing, GpuBuffer scratch) {
    }

    /** One ordered indexed geometry in a multi-geometry BLAS. */
    public record GeometryRange(int firstIndex, int indexCount, boolean opaque) {
        public GeometryRange {
            if (firstIndex < 0 || firstIndex % 3 != 0) {
                throw new IllegalArgumentException("firstIndex must be a non-negative triangle boundary");
            }
            if (indexCount <= 0 || indexCount % 3 != 0) {
                throw new IllegalArgumentException("indexCount must contain complete triangles");
            }
        }

        public int triangleCount() { return indexCount / 3; }
    }

    /** Immutable classified build whose source allocation is replaced by a compact copy before publication. */
    public record CompactableBuild(PreparedBlas op, RtAccel accel, GpuBuffer backing, GpuBuffer scratch) {
    }

    /** Explicit source and destination ownership for the second phase of a compactable BLAS build. */
    public record PreparedBlasCompaction(PreparedBlas source, RtAccel compactedAccel,
                                         GpuBuffer compactedBacking) {
    }

    /** Allocate a BLAS (AS + backing + scratch) and query sizes, deferring the build to {@link #recordBlasBuilds}. */
    public static PreparedBlas prepareTrianglesBlas(VulkanDeviceContext ctx, GpuBuffer positions, int vertexCount,
                                                    GpuBuffer indices, int indexCount, boolean opaque, String label) {
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "BLAS");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryBlasSizes(vk, stack, positions, indices, vertexCount, indexCount, opaque, false);
            GpuBuffer backing = ctx.createAsyncBuffer(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    debugLabel + " backing");
            GpuBuffer scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), debugLabel + " build scratch");
            RtAccel accel = createBlasOn(ctx, stack, backing, sizes.accelerationStructureSize(), true, debugLabel);
            return new PreparedBlas(accel, scratch, null, positions.deviceAddress(), indices.deviceAddress(), vertexCount - 1,
                    indexCount / 3, opaque, debugLabel, false, false);
        }
    }

    /**
     * Allocate a retained packed BLAS split into fixed SBT classes (any-hit opt). {@code classTris}
     * holds triangle counts in {@link #SBT_CLASSES} order: opaque, masked, transmissive. All geometries
     * reference the same packed vertex/index buffers; zero-triangle classes are kept so
     * {@code gl_GeometryIndexEXT} remains a stable material/SBT index in the shaders.
     */
    public static PreparedBlas prepareRetainedBlas(VulkanDeviceContext ctx, GpuBuffer positions, int vertexCount,
                                                   GpuBuffer indices, int[] classTris,
                                                   OpacityMicromapInput opacityMicromapInput,
                                                   boolean compact, String label) {
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "retained BLAS");
        OpacityMicromap opacityMicromap = null;
        GpuBuffer backing = null;
        GpuBuffer scratch = null;
        RtAccel accel = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            opacityMicromap = prepareOpacityMicromap(ctx, opacityMicromapInput, debugLabel);
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryRetainedBlasSizes(vk, stack, positions, indices,
                    vertexCount, classTris, opacityMicromap, compact);
            backing = ctx.createAsyncBuffer(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    debugLabel + " backing");
            scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), debugLabel + " build scratch");
            accel = createBlasOn(ctx, stack, backing, sizes.accelerationStructureSize(), true, debugLabel, opacityMicromap);
            if (compact) {
                VkQueryPoolCreateInfo queryCi = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                        .queryType(VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR).queryCount(1);
                java.nio.LongBuffer pQueryPool = stack.mallocLong(1);
                VulkanDeviceContext.check(VK10.vkCreateQueryPool(vk, queryCi, null, pQueryPool),
                        "vkCreateQueryPool(retained BLAS compacted size)");
                accel.compactionQueryPool = pQueryPool.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_QUERY_POOL, accel.compactionQueryPool,
                        debugLabel + " compacted-size query");
            }
            return PreparedBlas.retained(accel, scratch, null, positions.deviceAddress(), indices.deviceAddress(), vertexCount - 1,
                    classTris, opacityMicromap, debugLabel);
        } catch (Throwable t) {
            if (accel != null) {
                accel.destroy();
                if (scratch != null) scratch.destroy();
            } else {
                if (scratch != null) scratch.destroy();
                if (backing != null) backing.destroy();
                if (opacityMicromap != null) opacityMicromap.destroy();
            }
            throw t;
        }
    }

    /**
     * Read a completed retained build's compacted-size query and allocate its compact-copy destination.
     * Called only after the compute timeline confirms the build/query submission completed.
     */
    public static PreparedBlasCompaction prepareBlasCompaction(VulkanDeviceContext ctx, PreparedBlas source) {
        if ((!source.retainedSplit && !source.externalClassSplit) || source.accel.compactionQueryPool == 0L) {
            throw new IllegalArgumentException("BLAS has no pending compaction query");
        }
        long compactedSize;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.LongBuffer result = stack.mallocLong(1);
            VulkanDeviceContext.check(VK10.vkGetQueryPoolResults(ctx.vk(), source.accel.compactionQueryPool,
                    0, 1, result, Long.BYTES, VK10.VK_QUERY_RESULT_64_BIT),
                    "vkGetQueryPoolResults(retained BLAS compacted size)");
            compactedSize = result.get(0);
        }
        VK10.vkDestroyQueryPool(ctx.vk(), source.accel.compactionQueryPool, null);
        source.accel.compactionQueryPool = 0L;
        if (compactedSize <= 0L) {
            throw new IllegalStateException("retained BLAS compacted size is " + compactedSize);
        }

        GpuBuffer backing = null;
        RtAccel compactedAccel = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            backing = ctx.createAsyncBuffer(compactedSize,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    source.label + " compacted backing");
            compactedAccel = createBlasOn(ctx, stack, backing, compactedSize, false,
                    source.label + " compacted");
            OpacityMicromap opacityMicromap = source.accel.detachOpacityMicromap();
            compactedAccel.opacityMicromap = opacityMicromap;
            return new PreparedBlasCompaction(source, compactedAccel, backing);
        } catch (Throwable t) {
            if (compactedAccel != null) {
                compactedAccel.destroy();
            } else if (backing != null) {
                backing.destroy();
            }
            throw t;
        }
    }

    private static OpacityMicromap prepareOpacityMicromap(VulkanDeviceContext ctx, OpacityMicromapInput input,
                                                          String blasLabel) {
        if (input == null || input.triangleCount() <= 0) {
            return null;
        }
        VkDevice vk = ctx.vk();
        String label = blasLabel + " opacity micromap";
        int inputUsage = VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT;
        GpuBuffer data = null;
        GpuBuffer triangles = null;
        GpuBuffer backing = null;
        GpuBuffer scratch = null;
        long handle = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            data = ctx.createAsyncAlignedBuffer(input.data().length, inputUsage, true, label + " data",
                    MICROMAP_INPUT_ADDRESS_ALIGNMENT);
            long dataAddress = data.deviceAddress();
            MemoryUtil.memByteBuffer(data.mapped(), input.data().length).put(input.data());
            long triangleBytes = input.triangles().length;
            triangles = ctx.createAsyncAlignedBuffer(triangleBytes, inputUsage, true, label + " triangles",
                    MICROMAP_INPUT_ADDRESS_ALIGNMENT);
            long triangleArrayAddress = triangles.deviceAddress();
            MemoryUtil.memByteBuffer(triangles.mapped(), input.triangles().length).put(input.triangles());
            data.flush();
            triangles.flush();

            VkMicromapUsageEXT.Buffer usage = micromapUsage(stack, input.triangleCount(), input.subdivisionLevel());
            VkMicromapBuildInfoEXT build = micromapBuildInfo(stack, dataAddress, 0L, triangleArrayAddress, 0L, usage);
            VkMicromapBuildSizesInfoEXT sizes = VkMicromapBuildSizesInfoEXT.calloc(stack).sType$Default();
            vkGetMicromapBuildSizesEXT(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, build, sizes);

            backing = ctx.createAsyncBuffer(sizes.micromapSize(), VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT, false,
                    label + " backing");
            VkMicromapCreateInfoEXT ci = VkMicromapCreateInfoEXT.calloc(stack).sType$Default()
                    .buffer(backing.handle()).offset(0).size(sizes.micromapSize()).type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT);
            java.nio.LongBuffer pMicromap = stack.mallocLong(1);
            VulkanDeviceContext.check(vkCreateMicromapEXT(vk, ci, null, pMicromap), "vkCreateMicromapEXT");
            handle = pMicromap.get(0);
            RtDebugLabels.nameMicromap(ctx, handle, label);

            scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), label + " build scratch");
            long scratchAddress = scratchAddress(ctx, scratch);
            return new OpacityMicromap(vk, handle, backing, data, triangles, scratch, scratchAddress,
                    dataAddress, triangleArrayAddress, input.triangleCount(), input.subdivisionLevel(),
                    input.bytesPerTriangle(), null, input.bytesPerTriangle());
        } catch (Throwable t) {
            if (handle != 0L) vkDestroyMicromapEXT(vk, handle, null);
            if (scratch != null) scratch.destroy();
            if (backing != null) backing.destroy();
            if (triangles != null) triangles.destroy();
            if (data != null) data.destroy();
            throw t;
        }
    }

    private static OpacityMicromap prepareOpacityMicromap(VulkanDeviceContext ctx, OpacityMicromapGpuInput input,
                                                          String blasLabel) {
        if (input == null) return null;
        VkDevice vk = ctx.vk();
        String label = blasLabel + " opacity micromap";
        int inputUsage = VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT
                | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        GpuBuffer data = null;
        GpuBuffer triangles = null;
        GpuBuffer backing = null;
        GpuBuffer scratch = null;
        long handle = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int dataStride = input.dataStride();
            data = ctx.createAsyncAlignedBuffer((long) input.triangleCount() * dataStride,
                    inputUsage, false, label + " data", MICROMAP_INPUT_ADDRESS_ALIGNMENT);
            triangles = ctx.createAsyncAlignedBuffer((long) input.triangleCount() * VkMicromapTriangleEXT.SIZEOF,
                    inputUsage, false, label + " triangles", MICROMAP_INPUT_ADDRESS_ALIGNMENT);
            long dataAddress = data.deviceAddress();
            long triangleAddress = triangles.deviceAddress();
            VkMicromapUsageEXT.Buffer usage = micromapUsage(stack, input.triangleCount(), input.subdivisionLevel());
            VkMicromapBuildInfoEXT build = micromapBuildInfo(stack, dataAddress, 0L, triangleAddress, 0L, usage);
            VkMicromapBuildSizesInfoEXT sizes = VkMicromapBuildSizesInfoEXT.calloc(stack).sType$Default();
            vkGetMicromapBuildSizesEXT(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, build, sizes);
            backing = ctx.createAsyncBuffer(sizes.micromapSize(), VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT,
                    false, label + " backing");
            VkMicromapCreateInfoEXT create = VkMicromapCreateInfoEXT.calloc(stack).sType$Default()
                    .buffer(backing.handle()).offset(0).size(sizes.micromapSize())
                    .type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT);
            java.nio.LongBuffer output = stack.mallocLong(1);
            VulkanDeviceContext.check(vkCreateMicromapEXT(vk, create, null, output), "vkCreateMicromapEXT");
            handle = output.get(0);
            RtDebugLabels.nameMicromap(ctx, handle, label);
            scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), label + " build scratch");
            return new OpacityMicromap(vk, handle, backing, data, triangles, scratch,
                    scratchAddress(ctx, scratch), dataAddress, triangleAddress, input.triangleCount(),
                    input.subdivisionLevel(), input.bytesPerTriangle(), input.encoder(), dataStride);
        } catch (Throwable t) {
            if (handle != 0L) vkDestroyMicromapEXT(vk, handle, null);
            if (scratch != null) scratch.destroy();
            if (backing != null) backing.destroy();
            if (triangles != null) triangles.destroy();
            if (data != null) data.destroy();
            throw t;
        }
    }

    /**
     * Fully transient variant of {@link #prepareTrianglesBlas}. Its AS backing is caller-owned and must be
     * reclaimed with {@link #releaseTransientBlas}, not {@code freeBlasScratch} plus
     * {@code accel.destroy()}.
     */
    public static PreparedBlas prepareTransientBlas(VulkanDeviceContext ctx, GpuBuffer positions, int vertexCount,
                                                    GpuBuffer indices, int indexCount, boolean opaque, String label) {
        return prepareTransientBlas(ctx, positions.deviceAddress(), vertexCount,
                indices.deviceAddress(), indexCount, opaque, label);
    }

    /** Address-based variant for transient geometry packed into sub-regions of one owner buffer. */
    public static PreparedBlas prepareTransientBlas(VulkanDeviceContext ctx, long vertexAddr, int vertexCount,
                                                    long indexAddr, int indexCount, boolean opaque, String label) {
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "transient BLAS");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryBlasSizes(vk, stack, vertexAddr, indexAddr,
                    vertexCount, indexCount, opaque, false);
            GpuBuffer backing = ctx.createBuffer(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    debugLabel + " backing");
            GpuBuffer scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), debugLabel + " build scratch");
            RtAccel accel = createBlasOn(ctx, stack, backing, sizes.accelerationStructureSize(), false, debugLabel);
            return new PreparedBlas(accel, scratch, backing, vertexAddr, indexAddr, vertexCount - 1,
                    indexCount / 3, opaque, debugLabel, false, false);
        }
    }

    /** Caller-owned classified BLAS with packed indices in fixed {@link #SBT_CLASSES} order. */
    public static PreparedBlas prepareTransientBlas(VulkanDeviceContext ctx, long vertexAddr, int vertexCount,
                                                    long indexAddr, int[] classTriangles, String label) {
        return prepareTransientBlas(ctx, vertexAddr, vertexCount, indexAddr, classTriangles, label, false);
    }

    /**
     * Caller-owned classified BLAS with packed indices in fixed {@link #SBT_CLASSES} order. {@code fastBuild}
     * selects PREFER_FAST_BUILD instead of PREFER_FAST_TRACE, for geometry rebuilt from scratch every frame
     * (e.g. particles) where build latency dominates over trace quality.
     */
    public static PreparedBlas prepareTransientBlas(VulkanDeviceContext ctx, long vertexAddr, int vertexCount,
                                                    long indexAddr, int[] classTriangles, String label,
                                                    boolean fastBuild) {
        requireClassTriangles(classTriangles);
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "classified BLAS");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryClassifiedBlasSizes(vk, stack, vertexAddr,
                    indexAddr, vertexCount, classTriangles, false, fastBuild);
            GpuBuffer backing = ctx.createAsyncBuffer(sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false, debugLabel + " backing");
            GpuBuffer scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), debugLabel + " build scratch");
            RtAccel accel = createBlasOn(ctx, stack, backing, sizes.accelerationStructureSize(), false, debugLabel);
            return PreparedBlas.externalClassified(accel, scratch, backing, vertexAddr, indexAddr,
                    vertexCount - 1, classTriangles.clone(), debugLabel, false, false, fastBuild);
        }
    }

    /** Prepare a non-updatable persistent BLAS over packed caller-owned geometry. */
    public static PersistentBuild preparePersistentBlasBuild(VulkanDeviceContext ctx, long vertexAddr, int vertexCount,
                                                             long indexAddr, int indexCount, boolean opaque,
                                                             String label) {
        PreparedBlas op = prepareTransientBlas(ctx, vertexAddr, vertexCount, indexAddr, indexCount, opaque, label);
        return new PersistentBuild(op, op.accel, op.externalBacking, op.scratch);
    }

    public static PersistentBuild preparePersistentBlasBuild(VulkanDeviceContext ctx, long vertexAddr, int vertexCount,
                                                             long indexAddr, int[] classTriangles, String label) {
        PreparedBlas op = prepareTransientBlas(ctx, vertexAddr, vertexCount, indexAddr, classTriangles, label);
        return new PersistentBuild(op, op.accel, op.externalBacking, op.scratch);
    }

    /** Prepare a non-updatable persistent BLAS whose Vulkan geometry order matches {@code ranges}. */
    public static PersistentBuild preparePersistentBlasBuild(VulkanDeviceContext ctx, long vertexAddr, int vertexStride,
                                                             int vertexCount, long indexAddr,
                                                             List<GeometryRange> ranges,
                                                             String label) {
        List<GeometryRange> ordered = List.copyOf(ranges);
        if (ordered.isEmpty()) throw new IllegalArgumentException("a BLAS needs at least one geometry");
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "multi-geometry BLAS");
        GpuBuffer backing = null;
        GpuBuffer scratch = null;
        RtAccel accel = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryGeometryRangeBlasSizes(vk, stack,
                    vertexAddr, vertexStride, indexAddr, vertexCount, ordered);
            backing = ctx.createAsyncBuffer(sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false, debugLabel + " backing");
            scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), debugLabel + " build scratch");
            accel = createBlasOn(ctx, stack, backing, sizes.accelerationStructureSize(), false,
                    debugLabel);
            PreparedBlas op = new PreparedBlas(accel, scratch, backing, vertexAddr, indexAddr,
                    vertexCount - 1, vertexStride, ordered, debugLabel);
            return new PersistentBuild(op, accel, backing, scratch);
        } catch (Throwable failure) {
            if (accel != null) accel.destroy();
            if (scratch != null) scratch.destroy();
            if (backing != null) backing.destroy();
            throw failure;
        }
    }

    /** Prepare an immutable classified BLAS that writes a compacted-size query after its BUILD. */
    public static CompactableBuild prepareCompactableBlasBuild(VulkanDeviceContext ctx, long vertexAddr, int vertexCount,
                                                               long indexAddr, int[] classTriangles, String label) {
        requireClassTriangles(classTriangles);
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "compactable classified BLAS");
        GpuBuffer backing = null;
        GpuBuffer scratch = null;
        RtAccel accel = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryClassifiedBlasSizes(vk, stack, vertexAddr,
                    indexAddr, vertexCount, classTriangles, false, false, true);
            backing = ctx.createAsyncBuffer(sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false, debugLabel + " backing");
            scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), debugLabel + " build scratch");
            accel = createBlasOn(ctx, stack, backing, sizes.accelerationStructureSize(), false, debugLabel);
            VkQueryPoolCreateInfo queryCi = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR).queryCount(1);
            java.nio.LongBuffer pQueryPool = stack.mallocLong(1);
            VulkanDeviceContext.check(VK10.vkCreateQueryPool(vk, queryCi, null, pQueryPool),
                    "vkCreateQueryPool(BLAS compacted size)");
            accel.compactionQueryPool = pQueryPool.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_QUERY_POOL, accel.compactionQueryPool,
                    debugLabel + " compacted-size query");
            PreparedBlas op = PreparedBlas.externalClassified(accel, scratch, backing, vertexAddr, indexAddr,
                    vertexCount - 1, classTriangles.clone(), debugLabel, false, false);
            return new CompactableBuild(op, accel, backing, scratch);
        } catch (Throwable t) {
            if (accel != null) accel.destroy();
            if (scratch != null) scratch.destroy();
            if (backing != null) backing.destroy();
            throw t;
        }
    }

    /** Fresh immutable classified BUILD with GPU-generated opacity data for the masked geometry. */
    public static CompactableBuild prepareOpacityMicromapBlasBuild(
            VulkanDeviceContext ctx, long vertexAddr, int vertexCount, long indexAddr, int[] classTriangles,
            OpacityMicromapGpuInput opacityInput, boolean compact, String label) {
        requireClassTriangles(classTriangles);
        if (opacityInput.triangleCount() != classTriangles[CLASS_MASKED]) {
            throw new IllegalArgumentException("opacity triangle count must match the masked geometry");
        }
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "opacity micromap BLAS");
        OpacityMicromap opacity = null;
        GpuBuffer backing = null;
        GpuBuffer scratch = null;
        RtAccel accel = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            opacity = prepareOpacityMicromap(ctx, opacityInput, debugLabel);
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryRetainedBlasSizes(vk, stack,
                    vertexAddr, indexAddr, vertexCount, classTriangles, opacity, compact);
            backing = ctx.createAsyncBuffer(sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false, debugLabel + " backing");
            scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), debugLabel + " build scratch");
            accel = createBlasOn(ctx, stack, backing, sizes.accelerationStructureSize(), false,
                    debugLabel, opacity);
            if (compact) {
                VkQueryPoolCreateInfo queryInfo = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                        .queryType(VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR).queryCount(1);
                java.nio.LongBuffer query = stack.mallocLong(1);
                VulkanDeviceContext.check(VK10.vkCreateQueryPool(vk, queryInfo, null, query),
                        "vkCreateQueryPool(opacity micromap BLAS compacted size)");
                accel.compactionQueryPool = query.get(0);
            }
            PreparedBlas operation = PreparedBlas.retained(accel, scratch, backing, vertexAddr, indexAddr,
                    vertexCount - 1, classTriangles.clone(), opacity, debugLabel);
            return new CompactableBuild(operation, accel, backing, scratch);
        } catch (Throwable failure) {
            if (accel != null) accel.destroy();
            else if (opacity != null) opacity.destroy();
            if (scratch != null) scratch.destroy();
            if (backing != null) backing.destroy();
            throw failure;
        }
    }

    public static UpdatableBuild prepareUpdatableBlasBuild(VulkanDeviceContext ctx, long vertexAddr, int vertexCount,
                                                           long indexAddr, int[] classTriangles, String label) {
        requireClassTriangles(classTriangles);
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "updatable classified BLAS");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryClassifiedBlasSizes(vk, stack, vertexAddr,
                    indexAddr, vertexCount, classTriangles, true);
            long accelSize = sizes.accelerationStructureSize();
            GpuBuffer backing = ctx.createAsyncBuffer(accelSize,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false, debugLabel + " backing");
            GpuBuffer scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), debugLabel + " build scratch");
            RtAccel accel = createBlasOn(ctx, stack, backing, accelSize, false, debugLabel);
            PreparedBlas op = PreparedBlas.externalClassified(accel, scratch, backing, vertexAddr, indexAddr,
                    vertexCount - 1, classTriangles.clone(), debugLabel, true, false);
            return new UpdatableBuild(op, accel, backing, scratch, sizes.updateScratchSize());
        }
    }

    /**
     * Create a new <em>updatable</em> (ALLOW_UPDATE) BLAS sized for this mesh, and prepare its initial full
     * BUILD. The {@code accel} + {@code backing} persist in a caller-owned slot (not released per
     * frame); later frames refit it with {@link #refitUpdate} (cheap in-place UPDATE) while the topology is
     * stable, and free it with {@link #destroyCallerOwnedAccel} on eviction or topology change.
     */
    public static UpdatableBuild prepareUpdatableBlasBuild(VulkanDeviceContext ctx, GpuBuffer positions, int vertexCount,
                                                           GpuBuffer indices, int indexCount, boolean opaque, String label) {
        return prepareUpdatableBlasBuild(ctx, positions.deviceAddress(), vertexCount,
                indices.deviceAddress(), indexCount, opaque, label);
    }

    /** Address-based variant for geometry packed into sub-regions of one owner buffer. */
    public static UpdatableBuild prepareUpdatableBlasBuild(VulkanDeviceContext ctx, long vertexAddr, int vertexCount,
                                                           long indexAddr, int indexCount, boolean opaque, String label) {
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "updatable BLAS");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryBlasSizes(vk, stack, vertexAddr, indexAddr,
                    vertexCount, indexCount, opaque, true);
            long accelSize = sizes.accelerationStructureSize();
            long updateScratch = sizes.updateScratchSize();
            GpuBuffer backing = ctx.createAsyncBuffer(accelSize, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    debugLabel + " backing");
            GpuBuffer scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), debugLabel + " build scratch");
            RtAccel accel = createBlasOn(ctx, stack, backing, accelSize, false, debugLabel);
            PreparedBlas op = new PreparedBlas(accel, scratch, backing, vertexAddr, indexAddr,
                    vertexCount - 1, indexCount / 3, opaque, debugLabel, true, false);
            return new UpdatableBuild(op, accel, backing, scratch, updateScratch);
        }
    }

    /**
     * Prepare an in-place refit (UPDATE) of an existing updatable BLAS with new vertex data of the SAME
     * topology. {@code scratch} (sized {@code updateScratchSize}) and the mesh buffers are caller-owned
     * per-frame transients; the {@code accel} persists. Records nothing on its own — returned to {@link
     * #recordBlasBuilds} like a BUILD.
     */
    public static PreparedBlas refitUpdate(RtAccel accel, GpuBuffer scratch, long vertexAddr, long indexAddr,
                                           int vertexCount, int indexCount, boolean opaque, String label) {
        String debugLabel = labelOr(label, "BLAS refit");
        return new PreparedBlas(accel, scratch, null, vertexAddr, indexAddr, vertexCount - 1, indexCount / 3,
                opaque, debugLabel, true, true);
    }

    public static PreparedBlas refitUpdate(RtAccel accel, GpuBuffer scratch, long vertexAddr, long indexAddr,
                                           int vertexCount, int[] classTriangles, String label) {
        requireClassTriangles(classTriangles);
        return PreparedBlas.externalClassified(accel, scratch, null, vertexAddr, indexAddr, vertexCount - 1,
                classTriangles.clone(), labelOr(label, "classified BLAS refit"), true, true);
    }

    /**
     * Prepares an UPDATE into a fresh non-aliasing destination BLAS. The source must have been built
     * with ALLOW_UPDATE using the same classified topology and build flags.
     */
    public static UpdatableBuild prepareOutOfPlaceUpdate(VulkanDeviceContext ctx, RtAccel source,
                                                         long vertexAddr, int vertexCount, long indexAddr,
                                                         int[] classTriangles, String label) {
        requireClassTriangles(classTriangles);
        String debugLabel = labelOr(label, "classified BLAS update");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryClassifiedBlasSizes(ctx.vk(), stack, vertexAddr,
                    indexAddr, vertexCount, classTriangles, true);
            long accelSize = sizes.accelerationStructureSize();
            GpuBuffer backing = ctx.createAsyncBuffer(accelSize, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR,
                    false, debugLabel + " backing");
            GpuBuffer scratch = createScratchBuffer(ctx, sizes.updateScratchSize(), debugLabel + " scratch");
            RtAccel destination = createBlasOn(ctx, stack, backing, accelSize, false, debugLabel);
            PreparedBlas op = PreparedBlas.externalClassified(destination, scratch, backing, vertexAddr, indexAddr,
                    vertexCount - 1, classTriangles.clone(), debugLabel, true, true, source, false);
            return new UpdatableBuild(op, destination, backing, scratch, sizes.updateScratchSize());
        }
    }

    /** Refit-vs-rebuild outcome for one persistent updatable BLAS slot; see {@link #refitDecision}. */
    public enum RefitDecision {
        /** In-place UPDATE via {@link #refitUpdate}. */
        REFIT,
        /** A fresh BUILD via {@link #prepareUpdatableBlasBuild}, replacing the slot's AS. */
        BUILD
    }

    /**
     * Refit-vs-rebuild policy for one persistent updatable BLAS slot, evaluated per update.
     * {@code refitEnabled} and {@code slotIsUpdatable} gate whether UPDATE is available at all (config
     * off, or the slot's current AS was built without ALLOW_UPDATE); {@code hasAccel} is false only on
     * a slot's first build. {@code sameTopology} is the caller's own judgement of whether this update's
     * vertex/index data is compatible with the slot's last BUILD for MODE_UPDATE — an exact comparison
     * is safe here, but any cheaper equivalence the caller can establish is equally valid, since UPDATE
     * against incompatible topology is undefined behavior this function cannot itself detect.
     * {@code updatesSinceBuild} vs. {@code refitCountLimit} bounds BVH quality loss accumulated across
     * repeated refits — it counts refits, not frames, so a slot that stops updating never approaches
     * the limit.
     */
    public static RefitDecision refitDecision(boolean refitEnabled, boolean hasAccel, boolean slotIsUpdatable,
                                              boolean sameTopology, int updatesSinceBuild, int refitCountLimit) {
        if (!refitEnabled || !hasAccel || !slotIsUpdatable || !sameTopology) {
            return RefitDecision.BUILD;
        }
        return updatesSinceBuild < refitCountLimit ? RefitDecision.REFIT : RefitDecision.BUILD;
    }

    /** Reclaim a transient BLAS: destroy its AS handle, then its backing and scratch buffers. */
    public static void releaseTransientBlas(PreparedBlas blas) {
        blas.accel.destroy(); // ownsBacking == false → destroys only the AS handle, not the backing buffer
        blas.externalBacking.destroy();
        blas.scratch.destroy();
    }

    /** Destroy a caller-owned-backing persistent AS: destroy the handle, then its backing buffer. */
    public static void destroyCallerOwnedAccel(RtAccel accel, GpuBuffer backing) {
        accel.destroy(); // ownsBacking == false → handle only
        backing.destroy();
    }

    private static VkAccelerationStructureBuildSizesInfoKHR queryBlasSizes(VkDevice vk, MemoryStack stack, GpuBuffer positions,
                                                                           GpuBuffer indices, int vertexCount, int indexCount, boolean opaque, boolean allowUpdate) {
        return queryBlasSizes(vk, stack, positions.deviceAddress(), indices.deviceAddress(),
                vertexCount, indexCount, opaque, allowUpdate);
    }

    private static VkAccelerationStructureBuildSizesInfoKHR queryBlasSizes(VkDevice vk, MemoryStack stack,
                                                                           long vertexAddr, long indexAddr,
                                                                           int vertexCount, int indexCount,
                                                                           boolean opaque, boolean allowUpdate) {
        VkAccelerationStructureGeometryKHR.Buffer geom = triangleGeometry(stack, vertexAddr, indexAddr,
                vertexCount, opaque);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(allowUpdate))
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(1).pGeometries(geom);
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                build.get(0), stack.ints(indexCount / 3), sizes);
        return sizes;
    }

    private static void requireClassTriangles(int[] classTriangles) {
        if (classTriangles == null || classTriangles.length != SBT_CLASSES) {
            throw new IllegalArgumentException("Expected " + SBT_CLASSES + " SBT class triangle counts");
        }
        for (int count : classTriangles) {
            if (count < 0) throw new IllegalArgumentException("Negative class triangle count");
        }
    }

    private static int buildFlags(boolean allowUpdate) {
        return buildFlags(allowUpdate, false);
    }

    private static int buildFlags(boolean allowUpdate, boolean fastBuild) {
        // ALLOW_DATA_ACCESS lets the closest-hit read vertex positions from the BLAS via
        // gl_HitTriangleVertexPositionsEXT (VK_KHR_ray_tracing_position_fetch) for the normal-map TBN.
        // Applied to every BLAS and the refit path, so the build/UPDATE flags stay
        // identical (a refit invariant) — this is the single shared flag source.
        // PREFER_FAST_BUILD and PREFER_FAST_TRACE are mutually exclusive quality hints; fastBuild is for
        // geometry that is rebuilt from scratch every frame and never traced across many frames, where
        // build latency dominates over trace quality.
        int trace = fastBuild ? VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR
                : VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
        return trace
                | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DATA_ACCESS_BIT_KHR
                | (allowUpdate ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR : 0);
    }

    private static RtAccel createBlasOn(VulkanDeviceContext ctx, MemoryStack stack, GpuBuffer backing, long accelSize,
                                        boolean ownsBacking, String label) {
        return createBlasOn(ctx, stack, backing, accelSize, ownsBacking, label, null);
    }

    private static RtAccel createBlasOn(VulkanDeviceContext ctx, MemoryStack stack, GpuBuffer backing, long accelSize,
                                        boolean ownsBacking, String label, OpacityMicromap opacityMicromap) {
        VkDevice vk = ctx.vk();
        VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                .buffer(backing.handle()).offset(0).size(accelSize).type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
        java.nio.LongBuffer pAs = stack.mallocLong(1);
        VulkanDeviceContext.check(vkCreateAccelerationStructureKHR(vk, ci, null, pAs), "vkCreateAccelerationStructureKHR");
        long handle = pAs.get(0);
        try {
            RtDebugLabels.nameAccelerationStructure(ctx, handle, label);
            VkAccelerationStructureDeviceAddressInfoKHR addrInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType$Default().accelerationStructure(handle);
            long deviceAddress = vkGetAccelerationStructureDeviceAddressKHR(vk, addrInfo);
            return new RtAccel(vk, handle, deviceAddress, backing, ownsBacking, opacityMicromap);
        } catch (Throwable t) {
            vkDestroyAccelerationStructureKHR(vk, handle, null);
            throw t;
        }
    }

    private static VkAccelerationStructureGeometryKHR.Buffer triangleGeometry(MemoryStack stack, long vertexAddr, long indexAddr, int vertexCount, boolean opaque) {
        VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        fillTriangleGeometry(geom.get(0), vertexAddr, indexAddr, vertexCount, opaque);
        return geom;
    }

    private static void fillTriangleGeometry(VkAccelerationStructureGeometryKHR geom, long vertexAddr, long indexAddr, int vertexCount, boolean opaque) {
        fillTriangleGeometry(geom, vertexAddr, 3 * Float.BYTES, indexAddr, vertexCount, opaque);
    }

    private static void fillTriangleGeometry(VkAccelerationStructureGeometryKHR geom, long vertexAddr,
                                             int vertexStride, long indexAddr, int vertexCount,
                                             boolean opaque) {
        geom.sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(opaque ? VK_GEOMETRY_OPAQUE_BIT_KHR : VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);
        var tri = geom.geometry().triangles();
        tri.sType$Default()
                .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT).vertexStride(vertexStride)
                .maxVertex(vertexCount - 1).indexType(VK10.VK_INDEX_TYPE_UINT32);
        tri.vertexData().deviceAddress(vertexAddr);
        tri.indexData().deviceAddress(indexAddr);
    }

    private static VkAccelerationStructureGeometryKHR.Buffer geometryRangeGeometries(
            MemoryStack stack, long vertexAddr, int vertexStride, long indexAddr, int vertexCount,
            List<GeometryRange> ranges) {
        VkAccelerationStructureGeometryKHR.Buffer geometries =
                VkAccelerationStructureGeometryKHR.calloc(ranges.size(), stack);
        for (int i = 0; i < ranges.size(); i++) {
            fillTriangleGeometry(geometries.get(i), vertexAddr, vertexStride, indexAddr, vertexCount,
                    ranges.get(i).opaque());
        }
        return geometries;
    }

    static VkAccelerationStructureBuildRangeInfoKHR.Buffer geometryRangeBuildRanges(
            MemoryStack stack, List<GeometryRange> ranges) {
        VkAccelerationStructureBuildRangeInfoKHR.Buffer nativeRanges =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(ranges.size(), stack);
        for (int i = 0; i < ranges.size(); i++) {
            GeometryRange range = ranges.get(i);
            nativeRanges.get(i).primitiveCount(range.triangleCount())
                    .primitiveOffset(Math.multiplyExact(range.firstIndex(), Integer.BYTES))
                    .firstVertex(0).transformOffset(0);
        }
        return nativeRanges;
    }

    private static VkAccelerationStructureBuildSizesInfoKHR queryGeometryRangeBlasSizes(
            VkDevice vk, MemoryStack stack, long vertexAddr, int vertexStride, long indexAddr,
            int vertexCount,
            List<GeometryRange> ranges) {
        VkAccelerationStructureGeometryKHR.Buffer geometries = geometryRangeGeometries(stack,
                vertexAddr, vertexStride, indexAddr, vertexCount, ranges);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.get(0).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(false)).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(geometries.capacity()).pGeometries(geometries);
        java.nio.IntBuffer maxPrimitives = stack.mallocInt(ranges.size());
        for (GeometryRange range : ranges) maxPrimitives.put(range.triangleCount());
        maxPrimitives.flip();
        VkAccelerationStructureBuildSizesInfoKHR sizes =
                VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                build.get(0), maxPrimitives, sizes);
        return sizes;
    }

    /** Fixed class geometry order; empty classes remain present so GeometryIndex/SBT routing is stable. */
    private static VkAccelerationStructureGeometryKHR.Buffer classifiedGeometries(MemoryStack stack,
                                                                                   long vertexAddr,
                                                                                   long indexAddr,
                                                                                   int vertexCount) {
        VkAccelerationStructureGeometryKHR.Buffer geometries =
                VkAccelerationStructureGeometryKHR.calloc(SBT_CLASSES, stack);
        for (int cls = 0; cls < SBT_CLASSES; cls++) {
            fillTriangleGeometry(geometries.get(cls), vertexAddr, indexAddr, vertexCount,
                    cls == CLASS_OPAQUE);
        }
        return geometries;
    }

    private static VkAccelerationStructureBuildRangeInfoKHR.Buffer classifiedBuildRanges(MemoryStack stack,
                                                                                          int[] classTriangles) {
        VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(SBT_CLASSES, stack);
        int triangleBase = 0;
        for (int cls = 0; cls < SBT_CLASSES; cls++) {
            int count = classTriangles[cls];
            ranges.get(cls).primitiveCount(count)
                    .primitiveOffset(triangleBase * 3 * Integer.BYTES)
                    .firstVertex(0).transformOffset(0);
            triangleBase += count;
        }
        return ranges;
    }

    private static VkAccelerationStructureBuildSizesInfoKHR queryClassifiedBlasSizes(VkDevice vk,
                                                                                     MemoryStack stack,
                                                                                     long vertexAddr,
                                                                                     long indexAddr,
                                                                                     int vertexCount,
                                                                                     int[] classTriangles,
                                                                                     boolean allowUpdate) {
        return queryClassifiedBlasSizes(vk, stack, vertexAddr, indexAddr, vertexCount, classTriangles,
                allowUpdate, false, false);
    }

    private static VkAccelerationStructureBuildSizesInfoKHR queryClassifiedBlasSizes(VkDevice vk,
                                                                                     MemoryStack stack,
                                                                                     long vertexAddr,
                                                                                     long indexAddr,
                                                                                     int vertexCount,
                                                                                     int[] classTriangles,
                                                                                     boolean allowUpdate,
                                                                                     boolean fastBuild) {
        return queryClassifiedBlasSizes(vk, stack, vertexAddr, indexAddr, vertexCount, classTriangles,
                allowUpdate, fastBuild, false);
    }

    private static VkAccelerationStructureBuildSizesInfoKHR queryClassifiedBlasSizes(VkDevice vk,
                                                                                     MemoryStack stack,
                                                                                     long vertexAddr,
                                                                                     long indexAddr,
                                                                                     int vertexCount,
                                                                                     int[] classTriangles,
                                                                                     boolean allowUpdate,
                                                                                     boolean fastBuild,
                                                                                     boolean allowCompaction) {
        VkAccelerationStructureGeometryKHR.Buffer geometries = classifiedGeometries(stack, vertexAddr,
                indexAddr, vertexCount);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.get(0).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(allowUpdate, fastBuild)
                        | (allowCompaction ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR : 0))
                .geometryCount(geometries.capacity()).pGeometries(geometries);
        java.nio.IntBuffer maxPrims = stack.mallocInt(SBT_CLASSES);
        maxPrims.put(classTriangles).flip();
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                build.get(0), maxPrims, sizes);
        return sizes;
    }

    private static VkMicromapUsageEXT.Buffer micromapUsage(MemoryStack stack, int triangleCount, int subdivisionLevel) {
        VkMicromapUsageEXT.Buffer usage = VkMicromapUsageEXT.calloc(1, stack);
        usage.get(0).count(triangleCount)
                .subdivisionLevel(subdivisionLevel)
                .format(VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
        return usage;
    }

    private static VkMicromapBuildInfoEXT micromapBuildInfo(MemoryStack stack, long dataAddr, long scratchAddr,
                                                            long triangleArrayAddr, long dstMicromap,
                                                            VkMicromapUsageEXT.Buffer usage) {
        VkMicromapBuildInfoEXT build = VkMicromapBuildInfoEXT.calloc(stack).sType$Default()
                .type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT)
                .flags(VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT)
                .mode(VK_BUILD_MICROMAP_MODE_BUILD_EXT)
                .dstMicromap(dstMicromap)
                .usageCountsCount(usage.capacity())
                .pUsageCounts(usage)
                .triangleArrayStride(VkMicromapTriangleEXT.SIZEOF);
        build.data().deviceAddress(dataAddr);
        build.scratchData().deviceAddress(scratchAddr);
        build.triangleArray().deviceAddress(triangleArrayAddr);
        return build;
    }

    /** One triangle geometry per SBT class, in {@link #SBT_CLASSES} order; only opaque is flagged opaque. */
    private static VkAccelerationStructureGeometryKHR.Buffer retainedGeometries(MemoryStack stack, long vertexAddr,
                                                                                long indexAddr, int vertexCount, int[] classTris,
                                                                                OpacityMicromap opacityMicromap) {
        VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(classTris.length, stack);
        VkAccelerationStructureTrianglesOpacityMicromapEXT ommAttachment = null;
        if (opacityMicromap != null && classTris[CLASS_MASKED] > 0) {
            VkMicromapUsageEXT.Buffer usage = micromapUsage(stack, opacityMicromap.triangleCount, opacityMicromap.subdivisionLevel);
            ommAttachment = VkAccelerationStructureTrianglesOpacityMicromapEXT.calloc(stack).sType$Default()
                    .indexType(VK_INDEX_TYPE_NONE_KHR)
                    .indexStride(0L)
                    .baseTriangle(0)
                    .usageCountsCount(usage.capacity())
                    .pUsageCounts(usage)
                    .micromap(opacityMicromap.handle);
            ommAttachment.indexBuffer().deviceAddress(0L);
        }
        for (int b = 0; b < classTris.length; b++) {
            VkAccelerationStructureGeometryKHR out = geom.get(b);
            fillTriangleGeometry(out, vertexAddr, indexAddr, vertexCount, b == CLASS_OPAQUE);
            if (b == CLASS_MASKED && ommAttachment != null) {
                out.geometry().triangles().pNext(ommAttachment.address());
            }
        }
        return geom;
    }

    /** Build ranges parallel to {@link #retainedGeometries}; empty classes get a zero primitive count. */
    private static VkAccelerationStructureBuildRangeInfoKHR.Buffer retainedBuildRanges(MemoryStack stack, int[] classTris) {
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(classTris.length, stack);
        int acc = 0;
        for (int b = 0; b < classTris.length; b++) {
            int tris = classTris[b];
            range.get(b).primitiveCount(tris).primitiveOffset(acc * 3 * Integer.BYTES).firstVertex(0).transformOffset(0);
            acc += tris;
        }
        return range;
    }

    private static VkAccelerationStructureBuildSizesInfoKHR queryRetainedBlasSizes(VkDevice vk, MemoryStack stack, GpuBuffer positions,
                                                                                  GpuBuffer indices, int vertexCount, int[] classTris,
                                                                                  OpacityMicromap opacityMicromap, boolean compact) {
        VkAccelerationStructureGeometryKHR.Buffer geom = retainedGeometries(stack, positions.deviceAddress(), indices.deviceAddress(),
                vertexCount, classTris, opacityMicromap);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(false) | (compact ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR : 0))
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(geom.capacity()).pGeometries(geom);
        java.nio.IntBuffer maxPrims = stack.mallocInt(geom.capacity());
        for (int tris : classTris) {
            maxPrims.put(tris);
        }
        maxPrims.flip();
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                build.get(0), maxPrims, sizes);
        return sizes;
    }

    private static VkAccelerationStructureBuildSizesInfoKHR queryRetainedBlasSizes(
            VkDevice vk, MemoryStack stack, long vertexAddress, long indexAddress, int vertexCount,
            int[] classTris, OpacityMicromap opacityMicromap, boolean compact) {
        VkAccelerationStructureGeometryKHR.Buffer geom = retainedGeometries(stack, vertexAddress, indexAddress,
                vertexCount, classTris, opacityMicromap);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR
                .calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(false) | (compact ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR : 0))
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(geom.capacity())
                .pGeometries(geom);
        java.nio.IntBuffer maxPrimitives = stack.mallocInt(geom.capacity());
        for (int triangles : classTris) maxPrimitives.put(triangles);
        maxPrimitives.flip();
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                .sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                build.get(0), maxPrimitives, sizes);
        return sizes;
    }

    private static void recordBlasBuildsRaw(VulkanDeviceContext ctx, VkCommandBuffer cmd, List<PreparedBlas> blas) {
        for (PreparedBlas b : blas) {
            try (MemoryStack stack = MemoryStack.stackPush()) { // per-iteration: avoid 64 KB stack overflow
                recordBlasBuild(ctx, cmd, stack, b);
            }
        }
    }

    /** Record labelled BLAS builds into the command buffer. */
    public static void recordBlasBuilds(VulkanDeviceContext ctx, VkCommandBuffer cmd, List<PreparedBlas> blas) {
        String label = blas.size() == 1 ? blas.get(0).label + (blas.get(0).update ? " refit" : " build")
                : "BLAS builds " + blas.size();
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, label)) {
            recordBlasBuildsRaw(ctx, cmd, blas);
        }
    }

    /** Free the transient scratch buffers of a set of prepared BLAS (only after their build completed). */
    public static void freeBlasScratch(List<PreparedBlas> blas) {
        for (PreparedBlas b : blas) {
            b.freeTransientBuildResources();
        }
    }

    /** Record the compact copy after {@link #prepareBlasCompaction} has sized its destination. */
    public static void recordBlasCompaction(VulkanDeviceContext ctx, VkCommandBuffer cmd,
                                            PreparedBlasCompaction compaction) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd,
                    compaction.source.label + " compact")) {
            accelerationStructureBuildBarrier(cmd, stack);
            VkCopyAccelerationStructureInfoKHR copy = VkCopyAccelerationStructureInfoKHR.calloc(stack)
                    .sType$Default()
                    .src(compaction.source.accel.handle)
                    .dst(compaction.compactedAccel.handle)
                    .mode(VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR);
            vkCmdCopyAccelerationStructureKHR(cmd, copy);
        }
    }

    /** Release the uncompacted source after the compact copy reaches timeline completion. */
    public static void finishBlasCompaction(PreparedBlasCompaction compaction) {
        destroyPreparedAccel(compaction.source);
    }

    /** Release both AS allocations after a failed compact-copy phase. Geometry buffers remain caller-owned. */
    public static void destroyBlasCompaction(PreparedBlasCompaction compaction) {
        compaction.compactedAccel.destroy();
        compaction.compactedBacking.destroy();
        destroyPreparedAccel(compaction.source);
    }

    private static void destroyPreparedAccel(PreparedBlas prepared) {
        prepared.accel.destroy();
        if (prepared.externalBacking != null) prepared.externalBacking.destroy();
    }

    private static void recordBlasBuild(VulkanDeviceContext ctx, VkCommandBuffer cmd, MemoryStack stack, PreparedBlas b) {
        if (b.geometryRanges != null) {
            recordGeometryRangeBlasBuild(ctx, cmd, stack, b);
            return;
        }
        if (b.retainedSplit) {
            recordRetainedBlasBuild(ctx, cmd, stack, b);
            return;
        }
        if (b.externalClassSplit) {
            recordClassifiedBlasBuild(ctx, cmd, stack, b);
            return;
        }
        VkAccelerationStructureGeometryKHR.Buffer geom = triangleGeometry(stack, b.vertexAddr, b.indexAddr, b.maxVertex + 1, b.opaque);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(b.updatable))
                .mode(b.update ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(1).pGeometries(geom)
                .dstAccelerationStructure(b.accel.handle);
        if (b.update) {
            build.get(0).srcAccelerationStructure((b.updateSource == null ? b.accel : b.updateSource).handle);
        }
        build.get(0).scratchData().deviceAddress(scratchAddress(ctx, b.scratch));
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        range.get(0).primitiveCount(b.triangleCount).primitiveOffset(0).firstVertex(0).transformOffset(0);
        PointerBuffer ppRange = stack.mallocPointer(1).put(0, range.address());
        vkCmdBuildAccelerationStructuresKHR(cmd, build, ppRange);
    }

    private static void recordGeometryRangeBlasBuild(VulkanDeviceContext ctx, VkCommandBuffer cmd,
                                                     MemoryStack stack, PreparedBlas b) {
        VkAccelerationStructureGeometryKHR.Buffer geometries = geometryRangeGeometries(stack,
                b.vertexAddr, b.vertexStride, b.indexAddr, b.maxVertex + 1, b.geometryRanges);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.get(0).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(false)).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(geometries.capacity()).pGeometries(geometries)
                .dstAccelerationStructure(b.accel.handle);
        build.get(0).scratchData().deviceAddress(scratchAddress(ctx, b.scratch));
        VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges =
                geometryRangeBuildRanges(stack, b.geometryRanges);
        PointerBuffer ppRanges = stack.mallocPointer(1).put(0, ranges.address());
        vkCmdBuildAccelerationStructuresKHR(cmd, build, ppRanges);
    }

    /** Record the fixed classified geometries as one BUILD or UPDATE. */
    private static void recordClassifiedBlasBuild(VulkanDeviceContext ctx, VkCommandBuffer cmd, MemoryStack stack,
                                                  PreparedBlas b) {
        boolean compact = b.requestsCompaction();
        if (compact) VK10.vkCmdResetQueryPool(cmd, b.accel.compactionQueryPool, 0, 1);
        VkAccelerationStructureGeometryKHR.Buffer geometries = classifiedGeometries(stack, b.vertexAddr,
                b.indexAddr, b.maxVertex + 1);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.get(0).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(b.updatable, b.fastBuild)
                        | (compact ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR : 0))
                .mode(b.update ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR
                        : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(geometries.capacity()).pGeometries(geometries)
                .dstAccelerationStructure(b.accel.handle);
        if (b.update) {
            build.get(0).srcAccelerationStructure((b.updateSource == null ? b.accel : b.updateSource).handle);
        }
        build.get(0).scratchData().deviceAddress(scratchAddress(ctx, b.scratch));
        VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges = classifiedBuildRanges(stack,
                b.externalClassTriangles);
        PointerBuffer ppRanges = stack.mallocPointer(1).put(0, ranges.address());
        vkCmdBuildAccelerationStructuresKHR(cmd, build, ppRanges);
        if (compact) {
            accelerationStructureBuildBarrier(cmd, stack);
            vkCmdWriteAccelerationStructuresPropertiesKHR(cmd, stack.longs(b.accel.handle),
                    VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR,
                    b.accel.compactionQueryPool, 0);
        }
    }

    /** Record a retained packed multi-geometry BUILD. Retained replacements allocate a new BLAS, so no UPDATE branch. */
    private static void recordRetainedBlasBuild(VulkanDeviceContext ctx, VkCommandBuffer cmd, MemoryStack stack, PreparedBlas b) {
        boolean compact = b.requestsCompaction();
        if (compact) {
            VK10.vkCmdResetQueryPool(cmd, b.accel.compactionQueryPool, 0, 1);
        }
        if (b.opacityMicromap != null) {
            if (b.opacityMicromap.encoder != null) {
                b.opacityMicromap.encoder.record(cmd, b.opacityMicromap.dataAddress,
                        b.opacityMicromap.triangleArrayAddress, b.opacityMicromap.dataStride);
                opacityClassificationBarrier(cmd, stack);
            }
            recordMicromapBuild(cmd, stack, b.opacityMicromap);
            micromapBuildBarrier(cmd, stack);
        }
        VkAccelerationStructureGeometryKHR.Buffer geom = retainedGeometries(stack, b.vertexAddr, b.indexAddr,
                b.maxVertex + 1, b.retainedClassTriangles, b.opacityMicromap);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(false) | (compact ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR : 0))
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(geom.capacity()).pGeometries(geom)
                .dstAccelerationStructure(b.accel.handle);
        build.get(0).scratchData().deviceAddress(scratchAddress(ctx, b.scratch));
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range = retainedBuildRanges(stack, b.retainedClassTriangles);
        PointerBuffer ppRange = stack.mallocPointer(1).put(0, range.address());
        vkCmdBuildAccelerationStructuresKHR(cmd, build, ppRange);
        if (compact) {
            accelerationStructureBuildBarrier(cmd, stack);
            vkCmdWriteAccelerationStructuresPropertiesKHR(cmd, stack.longs(b.accel.handle),
                    VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR,
                    b.accel.compactionQueryPool, 0);
        }
    }

    private static void recordMicromapBuild(VkCommandBuffer cmd, MemoryStack stack, OpacityMicromap opacityMicromap) {
        VkMicromapUsageEXT.Buffer usage = micromapUsage(stack, opacityMicromap.triangleCount, opacityMicromap.subdivisionLevel);
        VkMicromapBuildInfoEXT.Buffer build = VkMicromapBuildInfoEXT.calloc(1, stack);
        build.get(0).set(micromapBuildInfo(stack, opacityMicromap.dataAddress, opacityMicromap.scratchAddress,
                opacityMicromap.triangleArrayAddress, opacityMicromap.handle, usage));
        vkCmdBuildMicromapsEXT(cmd, build);
    }

    private static void micromapBuildBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_MICROMAP_BUILD_BIT_EXT)
                .srcAccessMask(VK_ACCESS_2_MICROMAP_WRITE_BIT_EXT)
                .dstStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)
                .dstAccessMask(VK_ACCESS_2_MICROMAP_READ_BIT_EXT);
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier);
        VK13.vkCmdPipelineBarrier2(cmd, dep);
    }

    private static void opacityClassificationBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_MICROMAP_BUILD_BIT_EXT)
                .dstAccessMask(VK_ACCESS_2_MICROMAP_READ_BIT_EXT);
        VK13.vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
    }

    private static void accelerationStructureBuildBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)
                .srcAccessMask(VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                .dstStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)
                .dstAccessMask(VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier);
        VK13.vkCmdPipelineBarrier2(cmd, dep);
    }

    private static String labelOr(String label, String fallback) {
        return label == null || label.isBlank() ? fallback : label;
    }
}
