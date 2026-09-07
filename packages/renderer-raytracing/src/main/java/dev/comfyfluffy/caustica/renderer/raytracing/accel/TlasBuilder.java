package dev.comfyfluffy.caustica.renderer.raytracing.accel;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;

import dev.comfyfluffy.caustica.engine.scene.SnapshotList;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsUse;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.util.Arrays;
import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCreateAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR;

/** Builds frame-owned top-level acceleration structures. */
public final class TlasBuilder {
    private static final long INSTANCE_ADDRESS_ALIGNMENT = 16L;

    private TlasBuilder() {
    }

    /** One top-level instance with its transform, BLAS address, visibility, and hit-record selection. */
    public record Instance(float[] transform3x4, VulkanDeviceAddress blasDeviceAddress, int customIndex, int mask,
                           int sbtRecordOffset) {
        public Instance(float[] transform3x4, VulkanDeviceAddress blasDeviceAddress, int customIndex) {
            this(transform3x4, blasDeviceAddress, customIndex, 0xFF, 0);
        }

        public Instance(float[] transform3x4, VulkanDeviceAddress blasDeviceAddress, int customIndex, int mask) {
            this(transform3x4, blasDeviceAddress, customIndex, mask, 0);
        }
    }

    /** Reusable structure-of-arrays staging for a frame's instances. */
    public static final class InstanceBatch {
        private static final int TRANSFORM_FLOATS = 12;
        float[] transforms = new float[0];
        VulkanDeviceAddress[] blasDeviceAddresses = new VulkanDeviceAddress[0];
        int[] customIndices = new int[0];
        int[] masks = new int[0];
        int[] sbtRecordOffsets = new int[0];
        private int size;

        public void reset(int expectedSize) {
            ensureCapacity(expectedSize);
            size = 0;
        }

        public void append(float[] transform3x4, float translationX, float translationY, float translationZ,
                           VulkanDeviceAddress blasDeviceAddress, int customIndex, int mask, int sbtRecordOffset) {
            ensureCapacity(size + 1);
            int transformOffset = size * TRANSFORM_FLOATS;
            System.arraycopy(transform3x4, 0, transforms, transformOffset, TRANSFORM_FLOATS);
            transforms[transformOffset + 3] += translationX;
            transforms[transformOffset + 7] += translationY;
            transforms[transformOffset + 11] += translationZ;
            blasDeviceAddresses[size] = blasDeviceAddress;
            customIndices[size] = customIndex;
            masks[size] = mask;
            sbtRecordOffsets[size] = sbtRecordOffset;
            size++;
        }

        public int size() {
            return size;
        }

        private void ensureCapacity(int required) {
            int current = blasDeviceAddresses.length;
            if (required <= current) return;
            int grown = Math.max(required, Math.max(16, current + current / 2));
            transforms = Arrays.copyOf(transforms, Math.multiplyExact(grown, TRANSFORM_FLOATS));
            blasDeviceAddresses = Arrays.copyOf(blasDeviceAddresses, grown);
            customIndices = Arrays.copyOf(customIndices, grown);
            masks = Arrays.copyOf(masks, grown);
            sbtRecordOffsets = Arrays.copyOf(sbtRecordOffsets, grown);
        }
    }

    /** A build-ready view whose resources remain owned through the frame's graphics completion. */
    public static final class Prepared {
        public final RtAccel accel;
        private final GpuBuffer instanceBuffer;
        private final GpuBuffer scratch;
        private final int instanceCount;
        private final String label;

        private Prepared(RtAccel accel, GpuBuffer instanceBuffer, GpuBuffer scratch, int instanceCount,
                         String label) {
            this.accel = accel;
            this.instanceBuffer = instanceBuffer;
            this.scratch = scratch;
            this.instanceCount = instanceCount;
            this.label = label;
        }
    }

    /** One frame's instance buffer, acceleration structure, and build scratch. */
    private static final class Slot {
        private RtAccel accel;
        private GpuBuffer instanceBuffer;
        private GpuBuffer scratch;

        private void destroy() {
            accel.destroy();
            instanceBuffer.destroy();
            scratch.destroy();
        }
    }

    /** Writes one scene instance using the Vulkan struct API. */
    @FunctionalInterface
    public interface InstanceWriter<T> {
        void write(T instance, VkAccelerationStructureInstanceKHR target);
    }

    /** Fresh frame-owned resources whose instance buffer has not yet been packed or flushed. */
    public static final class Reserved {
        private final Slot slot;
        private final int count;

        private Reserved(Slot slot, int count) {
            this.slot = slot;
            this.count = count;
        }
    }

    /** Allocates resources and registers their ownership on the graphics-use calling thread. */
    public static Reserved reserve(VulkanDeviceContext ctx, int count, GraphicsUse graphicsUse) {
        return new Reserved(createSlot(ctx, count, graphicsUse), count);
    }

    /**
     * Packs exactly the reserved instance count and flushes borrowed resources. The caller must join
     * this work before recording the build or ending the graphics use that owns the reservation.
     */
    public static <T> Prepared pack(Reserved reserved, List<T> instances, InstanceWriter<T> writer) {
        writeInstances(instances, reserved.slot.instanceBuffer.mapped(), writer);
        return finish(reserved.slot, reserved.count);
    }

    /** Packs every instance into fresh frame-owned input, acceleration-structure, and scratch storage. */
    public static <T> Prepared prepare(VulkanDeviceContext ctx, List<T> instances,
                                       InstanceWriter<T> writer, GraphicsUse graphicsUse) {
        return pack(reserve(ctx, instances.size(), graphicsUse), instances, writer);
    }

    static <T> void writeInstances(List<T> instances, long mapped, InstanceWriter<T> writer) {
        var records = VkAccelerationStructureInstanceKHR.create(mapped, instances.size());
        int index = 0;
        for (var page : SnapshotList.pagesOf(instances)) {
            for (T instance : page) writer.write(instance, records.get(index++));
        }
    }

    /** Pack two instance ranges into a TLAS allocated for this frame. */
    public static Prepared prepare(VulkanDeviceContext ctx, List<Instance> baseInstances,
                                   List<Instance> dynamicInstances, GraphicsUse graphicsUse) {
        int baseCount = baseInstances.size();
        int count = Math.addExact(baseCount, dynamicInstances.size());
        Slot slot = createSlot(ctx, count, graphicsUse);
        writeInstances(baseInstances, slot.instanceBuffer.mapped(), 0);
        writeInstances(dynamicInstances, slot.instanceBuffer.mapped(), baseCount);
        return finish(slot, count);
    }

    /** Pack staged instances into a TLAS allocated for this frame. */
    public static Prepared prepare(VulkanDeviceContext ctx, InstanceBatch instances, GraphicsUse graphicsUse) {
        int count = instances.size();
        Slot slot = createSlot(ctx, count, graphicsUse);
        writeInstances(instances, slot.instanceBuffer.mapped());
        return finish(slot, count);
    }

    private static Prepared finish(Slot slot, int count) {
        if (count > 0) {
            slot.instanceBuffer.flush(0L, (long) count * VkAccelerationStructureInstanceKHR.SIZEOF);
        }
        return new Prepared(slot.accel, slot.instanceBuffer, slot.scratch, count,
                "frame TLAS " + count + " instances");
    }

    private static void writeInstances(List<Instance> instances, long mapped, int firstInstance) {
        VkAccelerationStructureInstanceKHR.Buffer records = VkAccelerationStructureInstanceKHR.create(
                mapped + (long) firstInstance * VkAccelerationStructureInstanceKHR.SIZEOF, instances.size());
        for (int i = 0, count = instances.size(); i < count; i++) {
            Instance instance = instances.get(i);
            VkAccelerationStructureInstanceKHR record = records.get(i);
            record.transform().matrix().put(instance.transform3x4());
            record.instanceCustomIndex(instance.customIndex())
                    .mask(instance.mask())
                    .instanceShaderBindingTableRecordOffset(instance.sbtRecordOffset())
                    .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                    .accelerationStructureReference(instance.blasDeviceAddress().value());
        }
    }

    private static void writeInstances(InstanceBatch instances, long mapped) {
        VkAccelerationStructureInstanceKHR.Buffer records = VkAccelerationStructureInstanceKHR.create(
                mapped, instances.size);
        for (int i = 0; i < instances.size; i++) {
            VkAccelerationStructureInstanceKHR record = records.get(i);
            record.transform().matrix().put(instances.transforms, i * InstanceBatch.TRANSFORM_FLOATS,
                    InstanceBatch.TRANSFORM_FLOATS);
            record.instanceCustomIndex(instances.customIndices[i])
                    .mask(instances.masks[i])
                    .instanceShaderBindingTableRecordOffset(instances.sbtRecordOffsets[i])
                    .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                    .accelerationStructureReference(instances.blasDeviceAddresses[i].value());
        }
    }

    /**
     * Allocate this frame's TLAS sized exactly to {@code capacity}, retiring it once the frame completes.
     * A zero-instance scene still needs a buffer the build can address, so the allocation is never empty.
     */
    private static Slot createSlot(VulkanDeviceContext ctx, int capacity, GraphicsUse graphicsUse) {
        VkDevice vk = ctx.vk();
        String label = "frame TLAS (" + capacity + " instances)";
        Slot slot = new Slot();
        slot.instanceBuffer = ctx.createAlignedBuffer(
                (long) VkAccelerationStructureInstanceKHR.SIZEOF * Math.max(1, capacity),
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, true,
                label + " instance buffer", INSTANCE_ADDRESS_ALIGNMENT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = buildInfo(
                    stack, slot.instanceBuffer.deviceAddress());
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                    .sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    build.get(0), stack.ints(capacity), sizes);

            GpuBuffer backing = ctx.createBuffer(sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false, label + " backing");
            VkAccelerationStructureCreateInfoKHR createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                    .sType$Default().buffer(backing.handle()).offset(0).size(sizes.accelerationStructureSize())
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            java.nio.LongBuffer accelerationStructure = stack.mallocLong(1);
            VulkanDeviceContext.check(vkCreateAccelerationStructureKHR(vk, createInfo, null, accelerationStructure),
                    "vkCreateAccelerationStructureKHR");
            long handle = accelerationStructure.get(0);
            RtDebugLabels.nameAccelerationStructure(ctx, handle, label);
            slot.scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), label + " build scratch");
            VkAccelerationStructureDeviceAddressInfoKHR addressInfo =
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default()
                            .accelerationStructure(handle);
            slot.accel = new RtAccel(vk, handle,
                    new VulkanDeviceAddress(vkGetAccelerationStructureDeviceAddressKHR(vk, addressInfo)), backing);
        }
        // Registered only once every member exists, so retirement never runs against a half-built slot.
        graphicsUse.whenComplete(slot::destroy);
        return slot;
    }

    private static VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo(
            MemoryStack stack, VulkanDeviceAddress instanceBufferAddress) {
        VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geometry.sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
        geometry.geometry().instances().sType$Default().arrayOfPointers(false);
        geometry.geometry().instances().data().deviceAddress(instanceBufferAddress.value());
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(1).pGeometries(geometry);
        return build;
    }

    /** Record the prepared top-level build. */
    public static void record(VulkanDeviceContext ctx, VkCommandBuffer commandBuffer, Prepared prepared) {
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, commandBuffer, prepared.label + " build");
             MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = buildInfo(
                    stack, prepared.instanceBuffer.deviceAddress());
            build.get(0).dstAccelerationStructure(prepared.accel.handle);
            build.get(0).scratchData().deviceAddress(scratchAddress(ctx, prepared.scratch).value());
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0).primitiveCount(prepared.instanceCount).primitiveOffset(0).firstVertex(0)
                    .transformOffset(0);
            PointerBuffer ranges = stack.mallocPointer(1).put(0, range.address());
            vkCmdBuildAccelerationStructuresKHR(commandBuffer, build, ranges);
        }
    }

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
}
