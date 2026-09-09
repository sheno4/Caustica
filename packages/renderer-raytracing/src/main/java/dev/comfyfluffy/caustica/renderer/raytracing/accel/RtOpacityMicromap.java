package dev.comfyfluffy.caustica.renderer.raytracing.accel;

import dev.comfyfluffy.caustica.api.geometry.OpacityMicromap;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteOrder;
import java.util.ArrayList;

import static org.lwjgl.vulkan.EXTOpacityMicromap.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR;

/** One geometry's native micromap. Build inputs retire at completion; storage follows the owning BLAS. */
final class RtOpacityMicromap {
    final int geometryIndex;
    final long handle;
    private final OpacityMicromap input;
    private final GpuBuffer data;
    private final GpuBuffer triangles;
    private final GpuBuffer scratch;
    private final ResourceLifetime buildInputs;
    private final ResourceLifetime lifetime;
    private final long storageBytes;

    private RtOpacityMicromap(VulkanDeviceContext context, int geometryIndex, long handle,
                              OpacityMicromap input, GpuBuffer backing, GpuBuffer data,
                              GpuBuffer triangles, GpuBuffer scratch) {
        this.geometryIndex = geometryIndex;
        this.handle = handle;
        this.input = input;
        this.data = data;
        this.triangles = triangles;
        this.scratch = scratch;
        this.storageBytes = backing.size();
        buildInputs = new ResourceLifetime(scratch::destroy, triangles::destroy, data::destroy);
        lifetime = new ResourceLifetime(() -> vkDestroyMicromapEXT(context.vk(), handle, null),
                backing::destroy, buildInputs::close);
    }

    static RtOpacityMicromap create(VulkanDeviceContext context, int geometryIndex,
                                    OpacityMicromap input, String label) {
        var allocated = new ArrayList<Runnable>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = buildInfo(stack, input);
            var sizes = VkMicromapBuildSizesInfoEXT.calloc(stack).sType$Default();
            vkGetMicromapBuildSizesEXT(context.vk(), VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    info.get(0), sizes);
            var backing = context.createAsyncBuffer(sizes.micromapSize(), VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT,
                    false, label + " storage");
            allocated.add(backing::destroy);
            var create = VkMicromapCreateInfoEXT.calloc(stack).sType$Default()
                    .type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT).buffer(backing.handle()).size(sizes.micromapSize());
            var out = stack.mallocLong(1);
            context.checkDeviceResult(vkCreateMicromapEXT(context.vk(), create, null, out), "vkCreateMicromapEXT");
            long handle = out.get(0);
            allocated.add(() -> vkDestroyMicromapEXT(context.vk(), handle, null));
            // EXT micromap input and scratch addresses require 256-byte alignment.
            var data = context.createAlignedBuffer(input.byteSize(), VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT,
                    true, label + " data", 256);
            allocated.add(data::destroy);
            input.write(MemoryUtil.memByteBuffer(data.mapped(), input.byteSize()));
            data.flush();
            int triangleBytes = Math.multiplyExact(input.triangleCount(), VkMicromapTriangleEXT.SIZEOF);
            var triangles = context.createAlignedBuffer(triangleBytes, VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT,
                    true, label + " triangles", 256);
            allocated.add(triangles::destroy);
            writeTriangles(input, MemoryUtil.memByteBuffer(triangles.mapped(), triangleBytes).order(ByteOrder.LITTLE_ENDIAN));
            triangles.flush();
            var scratch = context.createAlignedBuffer(Math.max(256, sizes.buildScratchSize()),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false, label + " scratch", 256);
            allocated.add(scratch::destroy);
            return new RtOpacityMicromap(context, geometryIndex, handle, input, backing, data, triangles, scratch);
        } catch (Throwable failure) {
            ResourceLifetime.closeAfterFailure(failure, allocated.reversed().toArray(Runnable[]::new));
            throw failure;
        }
    }

    static void writeTriangles(OpacityMicromap input, java.nio.ByteBuffer bytes) {
        for (int triangle = 0; triangle < input.triangleCount(); triangle++) {
            bytes.putInt(triangle * input.bytesPerTriangle()).putShort((short) input.subdivisionLevel())
                    .putShort((short) VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
        }
    }

    private static VkMicromapUsageEXT.Buffer usage(MemoryStack stack, OpacityMicromap input) {
        var usage = VkMicromapUsageEXT.calloc(1, stack);
        usage.get(0).count(input.triangleCount()).subdivisionLevel(input.subdivisionLevel())
                .format(VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
        return usage;
    }

    static VkMicromapBuildInfoEXT.Buffer buildInfo(MemoryStack stack, OpacityMicromap input) {
        // Both usage-pointer alternatives share a count that LWJGL does not infer from either pointer.
        var info = VkMicromapBuildInfoEXT.calloc(1, stack);
        info.get(0).sType$Default().type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT)
                .flags(VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT).mode(VK_BUILD_MICROMAP_MODE_BUILD_EXT)
                .usageCountsCount(1).pUsageCounts(usage(stack, input)).triangleArrayStride(VkMicromapTriangleEXT.SIZEOF);
        return info;
    }

    /** Attachments use heap storage because a mesh may contain more geometries than fit on the LWJGL stack. */
    static ResourceLifetime attachAll(VkAccelerationStructureGeometryKHR.Buffer geometries,
            java.util.List<dev.comfyfluffy.caustica.support.SharedResource<RtOpacityMicromap>> maps) {
        if (maps.isEmpty()) return new ResourceLifetime();
        var attachments = VkAccelerationStructureTrianglesOpacityMicromapEXT.calloc(maps.size());
        VkMicromapUsageEXT.Buffer usages = null;
        try {
            usages = VkMicromapUsageEXT.calloc(maps.size());
            for (int i = 0; i < maps.size(); i++) {
                var map = maps.get(i).get();
                usages.get(i).count(map.input.triangleCount()).subdivisionLevel(map.input.subdivisionLevel())
                        .format(VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
                fillAttachment(attachments.get(i), usages.slice(i, 1), map.handle);
                geometries.get(map.geometryIndex).geometry().triangles().pNext(attachments.get(i).address());
            }
            return new ResourceLifetime(attachments::free, usages::free);
        } catch (Throwable failure) {
            var allocatedUsages = usages;
            ResourceLifetime.closeAfterFailure(failure, attachments::free,
                    () -> { if (allocatedUsages != null) allocatedUsages.free(); });
            throw failure;
        }
    }

    static VkAccelerationStructureTrianglesOpacityMicromapEXT attachment(
            MemoryStack stack, OpacityMicromap input, long handle) {
        return fillAttachment(VkAccelerationStructureTrianglesOpacityMicromapEXT.calloc(stack), usage(stack, input), handle);
    }

    private static VkAccelerationStructureTrianglesOpacityMicromapEXT fillAttachment(
            VkAccelerationStructureTrianglesOpacityMicromapEXT attachment, VkMicromapUsageEXT.Buffer usage, long handle) {
        return attachment.sType$Default()
                .indexType(KHRAccelerationStructure.VK_INDEX_TYPE_NONE_KHR).micromap(handle)
                .usageCountsCount(1).pUsageCounts(usage);
    }

    void record(VkCommandBuffer command, MemoryStack stack) {
        var info = buildInfo(stack, input);
        info.get(0).dstMicromap(handle);
        info.get(0).data().deviceAddress(data.deviceAddress().value());
        info.get(0).triangleArray().deviceAddress(triangles.deviceAddress().value());
        info.get(0).scratchData().deviceAddress(scratch.deviceAddress().value());
        vkCmdBuildMicromapsEXT(command, info);
        var event = new BuildEvent();
        if (event.isEnabled()) {
            event.triangles = input.triangleCount();
            event.subdivisionLevel = input.subdivisionLevel();
            event.storageBytes = storageBytes;
            var bytes = MemoryUtil.memByteBuffer(data.mapped(), input.byteSize());
            int cells = 1 << (2 * input.subdivisionLevel());
            for (int triangle = 0; triangle < input.triangleCount(); triangle++) {
                for (int cell = 0; cell < cells; cell++) {
                    int state = (bytes.get(triangle * input.bytesPerTriangle() + cell / 4) >>> (2 * (cell % 4))) & 3;
                    if (state == 0) event.transparent++;
                    else if (state == 1) event.opaque++;
                    else event.unknown++;
                }
            }
            event.commit();
        }
    }

    static void buildToBlas(VkCommandBuffer command, MemoryStack stack) {
        var barrier = VkMemoryBarrier2.calloc(1, stack);
        barrier.get(0).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_MICROMAP_BUILD_BIT_EXT)
                .srcAccessMask(VK_ACCESS_2_MICROMAP_WRITE_BIT_EXT)
                .dstStageMask(KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)
                .dstAccessMask(VK_ACCESS_2_MICROMAP_READ_BIT_EXT);
        VK13.vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
    }

    void releaseBuildInputs() { buildInputs.close(); }
    void destroy() { lifetime.close(); }

    @jdk.jfr.Name("dev.comfyfluffy.caustica.OpacityMicromapBuild")
    @jdk.jfr.Label("Opacity micromap build recorded")
    @jdk.jfr.Category({"Caustica", "Mesh"}) @jdk.jfr.StackTrace(false) @jdk.jfr.Enabled(false)
    static final class BuildEvent extends jdk.jfr.Event {
        public int triangles;
        public int subdivisionLevel;
        public int opaque;
        public int transparent;
        public int unknown;
        @jdk.jfr.DataAmount(jdk.jfr.DataAmount.BYTES) public long storageBytes;
    }
}
