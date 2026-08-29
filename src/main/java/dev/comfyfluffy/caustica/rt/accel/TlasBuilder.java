package dev.comfyfluffy.caustica.rt.accel;

import dev.comfyfluffy.caustica.rt.GpuBuffer;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.GraphicsUse;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.TrackedGraphicsUse;
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

/** Builds and reuses the top-level acceleration structure for a rendered frame. */
public final class TlasBuilder {
    private static final long INSTANCE_ADDRESS_ALIGNMENT = 16L;

    private TlasBuilder() {
    }

    /** One top-level instance with its transform, BLAS address, visibility, and hit-record selection. */
    public record Instance(float[] transform3x4, long blasDeviceAddress, int customIndex, int mask,
                           int sbtRecordOffset) {
        public Instance(float[] transform3x4, long blasDeviceAddress, int customIndex) {
            this(transform3x4, blasDeviceAddress, customIndex, 0xFF, 0);
        }

        public Instance(float[] transform3x4, long blasDeviceAddress, int customIndex, int mask) {
            this(transform3x4, blasDeviceAddress, customIndex, mask, 0);
        }
    }

    /** Reusable structure-of-arrays staging for a frame's instances. */
    public static final class InstanceBatch {
        private static final int TRANSFORM_FLOATS = 12;
        float[] transforms = new float[0];
        long[] blasDeviceAddresses = new long[0];
        int[] customIndices = new int[0];
        int[] masks = new int[0];
        int[] sbtRecordOffsets = new int[0];
        private int size;

        public void reset(int expectedSize) {
            ensureCapacity(expectedSize);
            size = 0;
        }

        public void append(float[] transform3x4, float translationX, float translationY, float translationZ,
                           long blasDeviceAddress, int customIndex, int mask, int sbtRecordOffset) {
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

    /** A build-ready view over a ring slot. The ring retains ownership of all resources. */
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

    /** Owns reusable per-frame instance buffers, acceleration structures, and scratch buffers. */
    public static final class Ring {
        private static final int SIZE = 4;
        private static final float GROWTH = 1.25f;
        private static final int MIN_CAPACITY = 1024;
        private final Slot[] slots = new Slot[SIZE];
        private int cursor;

        private static final class Slot {
            private RtAccel accel;
            private GpuBuffer instanceBuffer;
            private GpuBuffer scratch;
            private int capacity;
            private final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();

            private void destroy() {
                accel.destroy();
                instanceBuffer.destroy();
                scratch.destroy();
            }
        }

        /** Free all slots after the caller has made the device idle. */
        public void destroy() {
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] != null) {
                    slots[i].destroy();
                    slots[i] = null;
                }
            }
        }
    }

    /** Pack two instance ranges into the next reusable ring slot. */
    public static Prepared prepare(GpuContext ctx, List<Instance> baseInstances,
                                   List<Instance> dynamicInstances, Ring ring, GraphicsUse graphicsUse) {
        int baseCount = baseInstances.size();
        int count = Math.addExact(baseCount, dynamicInstances.size());
        Ring.Slot slot = selectSlot(ctx, ring, count);
        writeInstances(baseInstances, slot.instanceBuffer.mapped(), 0);
        writeInstances(dynamicInstances, slot.instanceBuffer.mapped(), baseCount);
        return finish(slot, count, graphicsUse);
    }

    /** Pack staged instances into the next reusable ring slot. */
    public static Prepared prepare(GpuContext ctx, InstanceBatch instances, Ring ring, GraphicsUse graphicsUse) {
        int count = instances.size();
        Ring.Slot slot = selectSlot(ctx, ring, count);
        writeInstances(instances, slot.instanceBuffer.mapped());
        return finish(slot, count, graphicsUse);
    }

    private static Ring.Slot selectSlot(GpuContext ctx, Ring ring, int count) {
        Ring.Slot slot = ring.slots[ring.cursor];
        if (slot != null) ctx.gpuExecutor().graphicsUseWaiter().await(slot.graphicsUse);
        if (slot == null || count > slot.capacity) {
            if (slot != null) slot.destroy();
            slot = createSlot(ctx, Math.max(Ring.MIN_CAPACITY, (int) (count * Ring.GROWTH)));
            ring.slots[ring.cursor] = slot;
        }
        ring.cursor = (ring.cursor + 1) % Ring.SIZE;
        return slot;
    }

    private static Prepared finish(Ring.Slot slot, int count, GraphicsUse graphicsUse) {
        if (count > 0) {
            slot.instanceBuffer.flush(0L, (long) count * VkAccelerationStructureInstanceKHR.SIZEOF);
        }
        slot.graphicsUse.mark(graphicsUse);
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
                    .accelerationStructureReference(instance.blasDeviceAddress());
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
                    .accelerationStructureReference(instances.blasDeviceAddresses[i]);
        }
    }

    private static Ring.Slot createSlot(GpuContext ctx, int capacity) {
        VkDevice vk = ctx.vk();
        String label = "TLAS ring slot (" + capacity + " instance capacity)";
        Ring.Slot slot = new Ring.Slot();
        slot.capacity = capacity;
        slot.instanceBuffer = ctx.createAlignedBuffer((long) VkAccelerationStructureInstanceKHR.SIZEOF * capacity,
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
            GpuContext.check(vkCreateAccelerationStructureKHR(vk, createInfo, null, accelerationStructure),
                    "vkCreateAccelerationStructureKHR");
            long handle = accelerationStructure.get(0);
            RtDebugLabels.nameAccelerationStructure(ctx, handle, label);
            slot.scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), label + " build scratch");
            VkAccelerationStructureDeviceAddressInfoKHR addressInfo =
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default()
                            .accelerationStructure(handle);
            slot.accel = new RtAccel(vk, handle,
                    vkGetAccelerationStructureDeviceAddressKHR(vk, addressInfo), backing);
        }
        return slot;
    }

    private static VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo(
            MemoryStack stack, long instanceBufferAddress) {
        VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geometry.sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
        geometry.geometry().instances().sType$Default().arrayOfPointers(false);
        geometry.geometry().instances().data().deviceAddress(instanceBufferAddress);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(1).pGeometries(geometry);
        return build;
    }

    /** Record the prepared top-level build. */
    public static void record(GpuContext ctx, VkCommandBuffer commandBuffer, Prepared prepared) {
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, commandBuffer, prepared.label + " build");
             MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = buildInfo(
                    stack, prepared.instanceBuffer.deviceAddress());
            build.get(0).dstAccelerationStructure(prepared.accel.handle);
            build.get(0).scratchData().deviceAddress(scratchAddress(ctx, prepared.scratch));
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0).primitiveCount(prepared.instanceCount).primitiveOffset(0).firstVertex(0)
                    .transformOffset(0);
            PointerBuffer ranges = stack.mallocPointer(1).put(0, range.address());
            vkCmdBuildAccelerationStructuresKHR(commandBuffer, build, ranges);
        }
    }

    private static GpuBuffer createScratchBuffer(GpuContext ctx, long requiredSize, String label) {
        long alignment = ctx.accelerationStructureScratchAlignment();
        return ctx.createAlignedBuffer(Math.max(requiredSize, alignment), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                false, label, alignment);
    }

    private static long scratchAddress(GpuContext ctx, GpuBuffer scratch) {
        long alignment = ctx.accelerationStructureScratchAlignment();
        if ((scratch.deviceAddress() & (alignment - 1L)) != 0L) {
            throw new IllegalStateException("Scratch device address 0x"
                    + Long.toUnsignedString(scratch.deviceAddress(), 16) + " is not aligned to " + alignment);
        }
        return scratch.deviceAddress();
    }
}
