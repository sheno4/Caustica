package dev.comfyfluffy.caustica.renderer.raytracing.accel;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
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
import org.lwjgl.vulkan.VkCopyAccelerationStructureInfoKHR;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanBarriers;

import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DATA_ACCESS_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdCopyAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdWriteAccelerationStructuresPropertiesKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCreateAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkDestroyAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR;

/**
 * Owns an acceleration-structure handle and its backing buffer. BLAS factories live here;
 * {@link TlasBuilder} owns frame-level TLAS preparation and reuse.
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
    private final ResourceLifetime lifetime;

    RtAccel(VkDevice vk, long handle, VulkanDeviceAddress deviceAddress, GpuBuffer backing) {
        this.handle = handle;
        this.deviceAddress = deviceAddress;
        this.backing = backing;
        // The handle must be destroyed before the storage it references.
        this.lifetime = new ResourceLifetime(() -> {
            if (handle != 0L) vkDestroyAccelerationStructureKHR(vk, handle, null);
        }, backing::destroy);
    }

    public long sizeBytes() {
        return backing.size();
    }

    public void destroy() {
        lifetime.close();
    }

    /**
     * Pending BUILD or UPDATE. Scratch is released after GPU completion; the mesh owner retains
     * the acceleration structure and backing until its final release.
     */
    public record PersistentBuild(RtAccel accel, GpuBuffer scratch,
                                  VulkanDeviceAddress vertexAddr, VulkanDeviceAddress indexAddr,
                                  String label, BlasOperation operation) {
    }

    /** One compacted-size query, retained until its recorded GPU work completes. */
    public static final class CompactionQuery implements AutoCloseable {
        private final VulkanDeviceContext context;
        private final long pool;

        public CompactionQuery(VulkanDeviceContext context) {
            this.context = context;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                        .queryType(VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR).queryCount(1);
                var result = stack.mallocLong(1);
                context.checkDeviceResult(VK10.vkCreateQueryPool(context.vk(), info, null, result),
                        "vkCreateQueryPool(BLAS compaction)");
                pool = result.get(0);
            }
        }

        /** Record after the source BUILD; the query reads that build's acceleration-structure writes. */
        public void record(VkCommandBuffer cmd, RtAccel source) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VK10.vkCmdResetQueryPool(cmd, pool, 0, 1);
                VulkanBarriers.accelerationStructureBuildToUpdate(cmd, stack);
                vkCmdWriteAccelerationStructuresPropertiesKHR(cmd, stack.longs(source.handle),
                        VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR, pool, 0);
            }
        }

        /** Read the byte size only after the submission containing the query has completed. */
        public long readSize() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var result = stack.mallocLong(1);
                context.checkDeviceResult(VK10.vkGetQueryPoolResults(context.vk(), pool, 0, 1, result,
                        Long.BYTES, VK10.VK_QUERY_RESULT_64_BIT), "vkGetQueryPoolResults(BLAS compaction)");
                return result.get(0);
            }
        }

        @Override
        public void close() {
            VK10.vkDestroyQueryPool(context.vk(), pool, null);
        }
    }

    /** Allocate a compact-copy destination that owns its backing buffer. */
    public static RtAccel prepareCompactedBlas(VulkanDeviceContext ctx, long size, String label) {
        String debugLabel = labelOr(label, "compacted BLAS");
        GpuBuffer backing = ctx.createAsyncBuffer(size, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR,
                false, debugLabel + " backing");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return createOn(ctx, stack, backing, size, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, debugLabel);
        } catch (Throwable failure) {
            backing.destroy();
            throw failure;
        }
    }

    /** Source and destination must remain alive until this compact copy completes. */
    public static void recordCompaction(VulkanDeviceContext ctx, VkCommandBuffer cmd, RtAccel source,
                                        RtAccel destination, String label) {
        try (MemoryStack stack = MemoryStack.stackPush();
             var ignored = RtDebugLabels.scope(ctx, cmd, labelOr(label, "BLAS compact copy"))) {
            // Compact-copy source reads use the acceleration-structure build stage and access scope.
            VulkanBarriers.accelerationStructureBuildToUpdate(cmd, stack);
            VkCopyAccelerationStructureInfoKHR copy = VkCopyAccelerationStructureInfoKHR.calloc(stack)
                    .sType$Default().src(source.handle).dst(destination.handle)
                    .mode(VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR);
            vkCmdCopyAccelerationStructureKHR(cmd, copy);
        }
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

    /** Geometry properties that Vulkan requires to remain identical between BUILD and UPDATE. */
    public record BlasLayout(int vertexStride, int vertexCount, List<GeometryRange> geometryRanges) {
        public BlasLayout {
            if (vertexStride < 3 * Float.BYTES) {
                throw new IllegalArgumentException("vertexStride must contain a float3 position");
            }
            if (vertexCount <= 0) throw new IllegalArgumentException("vertexCount must be positive");
            geometryRanges = List.copyOf(geometryRanges);
            if (geometryRanges.isEmpty()) throw new IllegalArgumentException("a BLAS needs at least one geometry");
        }
    }

    public enum BlasOperationMode { BUILD, UPDATE }

    /** Explicit Vulkan operation contract; UPDATE always reads a distinct source generation. */
    public record BlasOperation(BlasOperationMode mode, long sourceHandle, boolean updateable,
                                BlasLayout layout) {
        public BlasOperation {
            java.util.Objects.requireNonNull(mode, "mode");
            java.util.Objects.requireNonNull(layout, "layout");
            if (mode == BlasOperationMode.BUILD && sourceHandle != 0L) {
                throw new IllegalArgumentException("BUILD must not name a source acceleration structure");
            }
            if (mode == BlasOperationMode.UPDATE && (sourceHandle == 0L || !updateable)) {
                throw new IllegalArgumentException("UPDATE needs an updateable source acceleration structure");
            }
        }
    }

    static BlasOperation initialBuildOperation(BlasLayout layout, boolean updateable) {
        return new BlasOperation(BlasOperationMode.BUILD, 0L, updateable, layout);
    }

    static BlasOperation cowUpdateOperation(BlasOperation source, long sourceHandle) {
        if (!source.updateable()) {
            throw new IllegalArgumentException("source BLAS was not built with ALLOW_UPDATE");
        }
        return new BlasOperation(BlasOperationMode.UPDATE, sourceHandle, true, source.layout());
    }

    /** Prepare a non-updatable persistent BLAS whose Vulkan geometry order matches {@code ranges}. */
    public static PersistentBuild preparePersistentBlasBuild(VulkanDeviceContext ctx,
                                                             VulkanDeviceAddress vertexAddr, int vertexStride,
                                                             int vertexCount, VulkanDeviceAddress indexAddr,
                                                             List<GeometryRange> ranges,
                                                             String label) {
        return preparePersistentBlasBuild(ctx, vertexAddr, vertexStride, vertexCount, indexAddr,
                ranges, false, label);
    }

    /** Prepare an updateable initial BUILD for a persistent copy-on-write BLAS lineage. */
    public static PersistentBuild prepareUpdateablePersistentBlasBuild(VulkanDeviceContext ctx,
                                                                       VulkanDeviceAddress vertexAddr,
                                                                       int vertexStride, int vertexCount,
                                                                       VulkanDeviceAddress indexAddr,
                                                                       List<GeometryRange> ranges,
                                                                       String label) {
        return preparePersistentBlasBuild(ctx, vertexAddr, vertexStride, vertexCount, indexAddr,
                ranges, true, label);
    }

    private static PersistentBuild preparePersistentBlasBuild(VulkanDeviceContext ctx,
                                                               VulkanDeviceAddress vertexAddr,
                                                               int vertexStride, int vertexCount,
                                                               VulkanDeviceAddress indexAddr,
                                                               List<GeometryRange> ranges,
                                                               boolean updateable, String label) {
        return prepareBlas(ctx, vertexAddr, indexAddr,
                initialBuildOperation(new BlasLayout(vertexStride, vertexCount, ranges), updateable), label);
    }

    /**
     * Prepare an UPDATE into a fresh persistent destination. The source acceleration structure and its
     * input buffers must remain alive until the recorded UPDATE completes.
     */
    public static PersistentBuild preparePersistentBlasUpdate(VulkanDeviceContext ctx,
                                                               BlasOperation source, long sourceHandle,
                                                               VulkanDeviceAddress vertexAddr,
                                                               VulkanDeviceAddress indexAddr,
                                                               String label) {
        return prepareBlas(ctx, vertexAddr, indexAddr, cowUpdateOperation(source, sourceHandle), label);
    }

    private static PersistentBuild prepareBlas(VulkanDeviceContext ctx, VulkanDeviceAddress vertexAddr,
                                               VulkanDeviceAddress indexAddr, BlasOperation operation,
                                               String label) {
        BlasLayout layout = operation.layout();
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "multi-geometry BLAS");
        GpuBuffer backing = null;
        GpuBuffer scratch = null;
        RtAccel accel = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryGeometryRangeBlasSizes(vk, stack,
                    vertexAddr, indexAddr, layout, operation.updateable());
            backing = ctx.createAsyncBuffer(sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    debugLabel + " backing");
            long scratchSize = operation.mode() == BlasOperationMode.UPDATE
                    ? sizes.updateScratchSize() : sizes.buildScratchSize();
            scratch = createScratchBuffer(ctx, scratchSize, debugLabel + " scratch");
            accel = createOn(ctx, stack, backing, sizes.accelerationStructureSize(),
                    VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, debugLabel);
            return new PersistentBuild(accel, scratch, vertexAddr, indexAddr, debugLabel, operation);
        } catch (Throwable failure) {
            if (accel != null) accel.destroy();
            else if (backing != null) backing.destroy();
            if (scratch != null) scratch.destroy();
            throw failure;
        }
    }

    static int buildFlags(boolean allowUpdate) {
        // Position fetch needs ALLOW_DATA_ACCESS. BUILD and UPDATE must use identical flags.
        return VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DATA_ACCESS_BIT_KHR
                | (allowUpdate ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR
                        : VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR);
    }

    /** Takes ownership of backing only on success; failure releases only the newly created handle. */
    static RtAccel createOn(VulkanDeviceContext ctx, MemoryStack stack, GpuBuffer backing, long accelSize,
                            int type, String label) {
        VkDevice vk = ctx.vk();
        VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                .buffer(backing.handle()).offset(0).size(accelSize).type(type);
        java.nio.LongBuffer pAs = stack.mallocLong(1);
        VulkanDeviceContext.check(vkCreateAccelerationStructureKHR(vk, ci, null, pAs), "vkCreateAccelerationStructureKHR");
        long handle = pAs.get(0);
        try {
            RtDebugLabels.nameAccelerationStructure(ctx, handle, label);
            VkAccelerationStructureDeviceAddressInfoKHR addrInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType$Default().accelerationStructure(handle);
            VulkanDeviceAddress deviceAddress = new VulkanDeviceAddress(
                    vkGetAccelerationStructureDeviceAddressKHR(vk, addrInfo));
            return new RtAccel(vk, handle, deviceAddress, backing);
        } catch (Throwable t) {
            ResourceLifetime.closeAfterFailure(t, () -> vkDestroyAccelerationStructureKHR(vk, handle, null));
            throw t;
        }
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
            VkDevice vk, MemoryStack stack, VulkanDeviceAddress vertexAddr,
            VulkanDeviceAddress indexAddr, BlasLayout layout, boolean updateable) {
        VkAccelerationStructureGeometryKHR.Buffer geometries = geometryRangeGeometries(
                vertexAddr, layout.vertexStride(), indexAddr, layout.vertexCount(), layout.geometryRanges());
        java.nio.IntBuffer maxPrimitives = null;
        try {
            maxPrimitives = MemoryUtil.memAllocInt(layout.geometryRanges().size());
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            build.get(0).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(buildFlags(updateable)).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(geometries.capacity()).pGeometries(geometries);
            for (GeometryRange range : layout.geometryRanges()) maxPrimitives.put(range.triangleCount());
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

    /** Record one labelled BUILD or UPDATE into the command buffer. */
    public static void recordBlasBuild(VulkanDeviceContext ctx, VkCommandBuffer cmd, PersistentBuild build) {
        String label = build.label + " " + build.operation.mode().name().toLowerCase(java.util.Locale.ROOT);
        try (MemoryStack stack = MemoryStack.stackPush();
             var ignored = RtDebugLabels.scope(ctx, cmd, label)) {
            recordGeometryRangeBlasBuild(ctx, cmd, stack, build);
        }
    }

    private static void recordGeometryRangeBlasBuild(VulkanDeviceContext ctx, VkCommandBuffer cmd,
                                                     MemoryStack stack, PersistentBuild b) {
        BlasLayout layout = b.operation.layout();
        if (b.operation.mode() == BlasOperationMode.UPDATE) {
            VulkanBarriers.accelerationStructureBuildToUpdate(cmd, stack);
        }
        try (VkAccelerationStructureGeometryKHR.Buffer geometries = geometryRangeGeometries(
                b.vertexAddr, layout.vertexStride(), b.indexAddr, layout.vertexCount(), layout.geometryRanges());
             VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges =
                     geometryRangeBuildRanges(layout.geometryRanges())) {
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            build.get(0).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(buildFlags(b.operation.updateable()))
                    .mode(b.operation.mode() == BlasOperationMode.BUILD
                            ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR
                            : VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR)
                    .geometryCount(geometries.capacity()).pGeometries(geometries)
                    .srcAccelerationStructure(b.operation.sourceHandle())
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
