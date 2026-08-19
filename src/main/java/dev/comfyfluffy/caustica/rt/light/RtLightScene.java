package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.api.provider.LightDescriptor;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.api.gpu.GpuBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.util.ArrayList;
import java.util.List;

/** Current-frame portion of the source-neutral GPU light scene. */
public final class RtLightScene {
    private static final int MIN_CAPACITY_BYTES = 64 * 1024;
    private static final int RING_SIZE = 6;

    private Slot[] ring;
    private int cursor = -1;

    public Frame prepareFrame(GpuContext ctx, List<? extends LightDescriptor> descriptors,
                              double originX, double originY, double originZ,
                              double metersPerWorldUnit,
                              RtGpuExecutor.GraphicsUseWaiter waiter) {
        if (descriptors.isEmpty()) return Frame.EMPTY;

        ArrayList<LightDescriptor.Finite> finite = new ArrayList<>();
        ArrayList<LightDescriptor.Distant> distant = new ArrayList<>();
        for (LightDescriptor descriptor : descriptors) {
            switch (descriptor) {
                case LightDescriptor.Finite light -> finite.add(light);
                case LightDescriptor.Distant light -> {
                    if (luminance(light.illuminanceRedLux(), light.illuminanceGreenLux(),
                            light.illuminanceBlueLux()) > 0.0) distant.add(light);
                }
            }
        }
        RtRetainedLightSceneBuilder.Data finiteData = RtRetainedLightSceneBuilder.buildFinite(
                finite, originX, originY, originZ, metersPerWorldUnit, () -> false);
        int finiteCount = finiteData.lightCount();
        int totalCount = Math.addExact(finiteCount, distant.size());
        if (totalCount == 0) return Frame.EMPTY;

        long lightBytes = Math.multiplyExact((long) totalCount,
                RtRetainedLightSceneBuilder.GPU_FLOATS_PER_LIGHT * Float.BYTES);
        long nodeOffset = align16(lightBytes);
        long totalBytes = align16(Math.addExact(nodeOffset, finiteData.nodeBytes()));

        ensureRing(ctx);
        cursor = (cursor + 1) % ring.length;
        Slot slot = ring[cursor];
        waiter.await(slot.graphicsUse);
        ensureCapacity(ctx, slot, totalBytes);

        float[] lights = new float[Math.multiplyExact(totalCount,
                RtRetainedLightSceneBuilder.GPU_FLOATS_PER_LIGHT)];
        System.arraycopy(finiteData.packedLights(), 0, lights, 0, finiteData.packedLights().length);
        for (int i = 0; i < distant.size(); i++) {
            RtRetainedLightSceneBuilder.encodeDistant(lights,
                    (finiteCount + i) * RtRetainedLightSceneBuilder.GPU_FLOATS_PER_LIGHT,
                    distant.get(i));
        }
        MemoryUtil.memFloatBuffer(slot.buffer.mapped(), lights.length).put(lights);
        if (finiteData.packedNodes().length > 0) {
            MemoryUtil.memFloatBuffer(slot.buffer.mapped() + nodeOffset,
                    finiteData.packedNodes().length).put(finiteData.packedNodes());
        }
        slot.buffer.flush(0L, totalBytes);
        return new Frame(slot.buffer.deviceAddress(),
                finiteData.packedNodes().length > 0 ? slot.buffer.deviceAddress() + nodeOffset : 0L,
                finiteData.rootNodeIndex(), finiteCount, finiteCount, distant.size(),
                (float) metersPerWorldUnit, slot);
    }

    /** Attach slot lifetime only after the command buffer reading this frame submitted successfully. */
    public void markGraphicsUse(Frame frame, RtGpuExecutor.GraphicsUse graphicsUse) {
        if (frame.slot != null) frame.slot.graphicsUse.mark(graphicsUse);
    }

    private void ensureRing(GpuContext ctx) {
        if (ring != null) return;
        ring = new Slot[RING_SIZE];
        for (int i = 0; i < ring.length; i++) {
            ring[i] = new Slot(createBuffer(ctx, MIN_CAPACITY_BYTES, i), MIN_CAPACITY_BYTES);
        }
    }

    private void ensureCapacity(GpuContext ctx, Slot slot, long requiredBytes) {
        if (requiredBytes <= slot.capacityBytes) return;
        int capacity = capacityForBytes(requiredBytes);
        slot.buffer.destroy();
        slot.buffer = createBuffer(ctx, capacity, cursor);
        slot.capacityBytes = capacity;
    }

    static int capacityForBytes(long requiredBytes) {
        int capacity = MIN_CAPACITY_BYTES;
        while (capacity < requiredBytes) capacity = Math.multiplyExact(capacity, 2);
        return capacity;
    }

    private static long align16(long value) {
        return Math.addExact(value, 15L) & ~15L;
    }

    private static double luminance(double red, double green, double blue) {
        return Math.max(0.0, 0.27222872 * red + 0.67408177 * green + 0.05368952 * blue);
    }

    private static GpuBuffer createBuffer(GpuContext ctx, int bytes, int slotIndex) {
        return ctx.createBuffer(bytes, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true,
                "frame light scene " + slotIndex);
    }

    /** Called after the device is idle. */
    public void destroy() {
        if (ring == null) return;
        for (Slot slot : ring) slot.buffer.destroy();
        ring = null;
        cursor = -1;
    }

    public static final class Frame {
        private static final Frame EMPTY = new Frame(0L, 0L, -1, 0, 0, 0, 1.0f, null);
        private final long lightAddress;
        private final long nodeAddress;
        private final int rootNodeIndex;
        private final int finiteLightCount;
        private final int distantFirstLight;
        private final int distantLightCount;
        private final float metersPerWorldUnit;
        private final Slot slot;

        private Frame(long lightAddress, long nodeAddress, int rootNodeIndex, int finiteLightCount,
                      int distantFirstLight, int distantLightCount, float metersPerWorldUnit,
                      Slot slot) {
            this.lightAddress = lightAddress;
            this.nodeAddress = nodeAddress;
            this.rootNodeIndex = rootNodeIndex;
            this.finiteLightCount = finiteLightCount;
            this.distantFirstLight = distantFirstLight;
            this.distantLightCount = distantLightCount;
            this.metersPerWorldUnit = metersPerWorldUnit;
            this.slot = slot;
        }

        public long lightAddress() { return lightAddress; }
        public long nodeAddress() { return nodeAddress; }
        public int rootNodeIndex() { return rootNodeIndex; }
        public int finiteLightCount() { return finiteLightCount; }
        public int distantFirstLight() { return distantFirstLight; }
        public int distantLightCount() { return distantLightCount; }
        public float metersPerWorldUnit() { return metersPerWorldUnit; }
    }

    private static final class Slot {
        private GpuBuffer buffer;
        private int capacityBytes;
        private final RtGpuExecutor.TrackedGraphicsUse graphicsUse =
                new RtGpuExecutor.TrackedGraphicsUse();

        private Slot(GpuBuffer buffer, int capacityBytes) {
            this.buffer = buffer;
            this.capacityBytes = capacityBytes;
        }
    }
}
