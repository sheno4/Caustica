package dev.comfyfluffy.caustica.renderer.raytracing.accel;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;

import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DATA_ACCESS_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCreateAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkDestroyAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR;

/**
 * A built acceleration structure plus its backing buffer. BLAS factories and lifetime operations remain
 * here; {@link TlasBuilder} owns frame-level TLAS preparation and reuse.
 */
public final class RtAccel {
    private static GpuBuffer createScratchBuffer(VulkanDeviceContext ctx, long requiredSize, String label) {
        long alignment = ctx.accelerationStructureScratchAlignment();
        return ctx.createAlignedBuffer(Math.max(requiredSize, alignment), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                false, label, alignment);
    }

    private static VulkanDeviceAddress scratchAddress(VulkanDeviceContext ctx, GpuBuffer scratch) {
        long alignment = ctx.accelerationStructureScratchAlignment();
        if (!scratch.deviceAddress().isAlignedTo(alignment)) {
            throw new IllegalStateException("Scratch device address is not aligned to " + alignment);
        }
        return scratch.deviceAddress();
    }

    public final long handle;
    public final VulkanDeviceAddress deviceAddress;

    private final GpuBuffer backing;
    private final boolean ownsBacking;
    private final VkDevice vk;
    private boolean destroyed;

    RtAccel(VkDevice vk, long handle, VulkanDeviceAddress deviceAddress, GpuBuffer backing) {
        this(vk, handle, deviceAddress, backing, true);
    }

    private RtAccel(VkDevice vk, long handle, VulkanDeviceAddress deviceAddress, GpuBuffer backing,
                    boolean ownsBacking) {
        this.vk = vk;
        this.handle = handle;
        this.deviceAddress = deviceAddress;
        this.backing = backing;
        this.ownsBacking = ownsBacking;
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        if (handle != 0L) {
            vkDestroyAccelerationStructureKHR(vk, handle, null);
        }
        // Caller-owned backing is released separately from the acceleration-structure handle.
        if (ownsBacking) {
            backing.destroy();
        }
        destroyed = true;
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
        private final VulkanDeviceAddress vertexAddr;
        private final int vertexStride;
        private final VulkanDeviceAddress indexAddr;
        private final int maxVertex;
        private final int triangleCount;
        private final String label;
        private final List<GeometryRange> geometryRanges;


        private PreparedBlas(RtAccel accel, GpuBuffer scratch, GpuBuffer externalBacking,
                             VulkanDeviceAddress vertexAddr, VulkanDeviceAddress indexAddr, int maxVertex,
                             int vertexStride, List<GeometryRange> geometryRanges, String label) {
            this.accel = accel;
            this.scratch = scratch;
            this.externalBacking = externalBacking;
            this.vertexAddr = vertexAddr;
            this.vertexStride = vertexStride;
            this.indexAddr = indexAddr;
            this.maxVertex = maxVertex;
            this.triangleCount = geometryRanges.stream().mapToInt(GeometryRange::triangleCount).sum();
            this.label = label;
            this.geometryRanges = List.copyOf(geometryRanges);
        }

        private void freeTransientBuildResources() {
            scratch.destroy();
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
    public static final int SBT_HIT_GROUP_COUNT = SBT_CLASSES * 2;

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

    /** Prepare a non-updatable persistent BLAS whose Vulkan geometry order matches {@code ranges}. */
    public static PersistentBuild preparePersistentBlasBuild(VulkanDeviceContext ctx,
                                                             VulkanDeviceAddress vertexAddr, int vertexStride,
                                                             int vertexCount, VulkanDeviceAddress indexAddr,
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
                                                                           VulkanDeviceAddress vertexAddr,
                                                                           VulkanDeviceAddress indexAddr,
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
            VulkanDeviceAddress deviceAddress = new VulkanDeviceAddress(
                    vkGetAccelerationStructureDeviceAddressKHR(vk, addrInfo));
            return new RtAccel(vk, handle, deviceAddress, backing, ownsBacking);
        } catch (Throwable t) {
            vkDestroyAccelerationStructureKHR(vk, handle, null);
            throw t;
        }
    }

    private static VkAccelerationStructureGeometryKHR.Buffer triangleGeometry(MemoryStack stack,
                                                                               VulkanDeviceAddress vertexAddr,
                                                                               VulkanDeviceAddress indexAddr,
                                                                               int vertexCount, boolean opaque) {
        VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        fillTriangleGeometry(geom.get(0), vertexAddr, indexAddr, vertexCount, opaque);
        return geom;
    }

    private static void fillTriangleGeometry(VkAccelerationStructureGeometryKHR geom,
                                             VulkanDeviceAddress vertexAddr,
                                             VulkanDeviceAddress indexAddr, int vertexCount, boolean opaque) {
        fillTriangleGeometry(geom, vertexAddr, 3 * Float.BYTES, indexAddr, vertexCount, opaque);
    }

    private static void fillTriangleGeometry(VkAccelerationStructureGeometryKHR geom,
                                             VulkanDeviceAddress vertexAddr,
                                             int vertexStride, VulkanDeviceAddress indexAddr, int vertexCount,
                                             boolean opaque) {
        geom.sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(opaque ? VK_GEOMETRY_OPAQUE_BIT_KHR : VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);
        var tri = geom.geometry().triangles();
        tri.sType$Default()
                .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT).vertexStride(vertexStride)
                .maxVertex(vertexCount - 1).indexType(VK10.VK_INDEX_TYPE_UINT32);
        tri.vertexData().deviceAddress(vertexAddr.value());
        tri.indexData().deviceAddress(indexAddr.value());
    }

    static VkAccelerationStructureGeometryKHR.Buffer geometryRangeGeometries(
            VulkanDeviceAddress vertexAddr, int vertexStride,
            VulkanDeviceAddress indexAddr, int vertexCount,
            List<GeometryRange> ranges) {
        VkAccelerationStructureGeometryKHR.Buffer geometries =
                VkAccelerationStructureGeometryKHR.calloc(ranges.size());
        try {
            for (int i = 0; i < ranges.size(); i++) {
                fillTriangleGeometry(geometries.get(i), vertexAddr, vertexStride, indexAddr, vertexCount,
                        ranges.get(i).opaque());
            }
            return geometries;
        } catch (Throwable failure) {
            geometries.free();
            throw failure;
        }
    }

    static VkAccelerationStructureBuildRangeInfoKHR.Buffer geometryRangeBuildRanges(
            List<GeometryRange> ranges) {
        VkAccelerationStructureBuildRangeInfoKHR.Buffer nativeRanges =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(ranges.size());
        try {
            for (int i = 0; i < ranges.size(); i++) {
                GeometryRange range = ranges.get(i);
                nativeRanges.get(i).primitiveCount(range.triangleCount())
                        .primitiveOffset(Math.multiplyExact(range.firstIndex(), Integer.BYTES))
                        .firstVertex(0).transformOffset(0);
            }
            return nativeRanges;
        } catch (Throwable failure) {
            nativeRanges.free();
            throw failure;
        }
    }

    private static VkAccelerationStructureBuildSizesInfoKHR queryGeometryRangeBlasSizes(
            VkDevice vk, MemoryStack stack, VulkanDeviceAddress vertexAddr, int vertexStride,
            VulkanDeviceAddress indexAddr,
            int vertexCount,
        List<GeometryRange> ranges) {
        VkAccelerationStructureGeometryKHR.Buffer geometries = geometryRangeGeometries(
                vertexAddr, vertexStride, indexAddr, vertexCount, ranges);
        java.nio.IntBuffer maxPrimitives = null;
        try {
            maxPrimitives = MemoryUtil.memAllocInt(ranges.size());
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            build.get(0).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(buildFlags(false)).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(geometries.capacity()).pGeometries(geometries);
            for (GeometryRange range : ranges) maxPrimitives.put(range.triangleCount());
            maxPrimitives.flip();
            VkAccelerationStructureBuildSizesInfoKHR sizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    build.get(0), maxPrimitives, sizes);
            return sizes;
        } finally {
            if (maxPrimitives != null) MemoryUtil.memFree(maxPrimitives);
            geometries.free();
        }
    }

    private static void recordBlasBuildsRaw(VulkanDeviceContext ctx, VkCommandBuffer cmd, List<PreparedBlas> blas) {
        for (PreparedBlas b : blas) {
            try (MemoryStack stack = MemoryStack.stackPush()) { // per-iteration: avoid 64 KB stack overflow
                recordGeometryRangeBlasBuild(ctx, cmd, stack, b);
            }
        }
    }

    /** Record labelled BLAS builds into the command buffer. */
    public static void recordBlasBuilds(VulkanDeviceContext ctx, VkCommandBuffer cmd, List<PreparedBlas> blas) {
        String label = blas.size() == 1 ? blas.get(0).label + " build" : "BLAS builds " + blas.size();
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


    private static void recordGeometryRangeBlasBuild(VulkanDeviceContext ctx, VkCommandBuffer cmd,
                                                     MemoryStack stack, PreparedBlas b) {
        try (VkAccelerationStructureGeometryKHR.Buffer geometries = geometryRangeGeometries(
                b.vertexAddr, b.vertexStride, b.indexAddr, b.maxVertex + 1, b.geometryRanges);
             VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges =
                     geometryRangeBuildRanges(b.geometryRanges)) {
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            build.get(0).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(buildFlags(false)).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(geometries.capacity()).pGeometries(geometries)
                    .dstAccelerationStructure(b.accel.handle);
            build.get(0).scratchData().deviceAddress(scratchAddress(ctx, b.scratch).value());
            PointerBuffer ppRanges = stack.mallocPointer(1).put(0, ranges.address());
            vkCmdBuildAccelerationStructuresKHR(cmd, build, ppRanges);
        }
    }

    private static String labelOr(String label, String fallback) {
        return label == null || label.isBlank() ? fallback : label;
    }
}
