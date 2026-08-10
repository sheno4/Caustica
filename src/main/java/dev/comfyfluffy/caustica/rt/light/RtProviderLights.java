package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.engine.light.DistantLight;
import dev.comfyfluffy.caustica.engine.light.FiniteLight;
import dev.comfyfluffy.caustica.engine.light.LightDescriptor;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.accel.GpuBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.util.List;

/** Per-frame GPU snapshot of lights submitted through the public provider API. */
public final class RtProviderLights {
    static final int RECORD_BYTES = 64;
    private static final int MIN_CAPACITY = 1024;
    private static final int RING_SIZE = 6;

    private Slot[] ring;
    private int cursor = -1;

    public Frame writeFrame(GpuContext ctx, List<? extends LightDescriptor> descriptors,
                            double originX, double originY, double originZ,
                            RtGpuExecutor.GraphicsUseWaiter waiter) {
        if (descriptors.isEmpty()) {
            return Frame.EMPTY;
        }
        ensureRing(ctx);
        cursor = (cursor + 1) % ring.length;
        Slot slot = ring[cursor];
        waiter.await(slot.graphicsUse);
        ensureCapacity(ctx, slot, descriptors.size());

        long address = slot.buffer.mapped;
        for (int i = 0; i < descriptors.size(); i++) {
            writeRecord(address + (long) i * RECORD_BYTES, descriptors.get(i),
                    originX, originY, originZ, 1.0);
        }
        slot.buffer.flush(0L, (long) descriptors.size() * RECORD_BYTES);
        return new Frame(slot.buffer.deviceAddress, descriptors.size(), slot);
    }

    /** Attach a slot lifetime only after the command buffer that reads it was submitted successfully. */
    public void markGraphicsUse(Frame frame, RtGpuExecutor.GraphicsUse graphicsUse) {
        if (frame.slot != null) {
            frame.slot.graphicsUse.mark(graphicsUse);
        }
    }

    static float[] encode(LightDescriptor descriptor, double originX, double originY,
                          double originZ, double metersPerWorldUnit) {
        float[] result = new float[RECORD_BYTES / Float.BYTES];
        switch (descriptor) {
            case LightDescriptor.Rectangle light -> {
                FiniteLight.from(light, metersPerWorldUnit);
                putFloat3(result, 0, light.positionX() - originX,
                        light.positionY() - originY, light.positionZ() - originZ);
                result[3] = Float.intBitsToFloat(0);
                putFloat3(result, 4, light.halfUx(), light.halfUy(), light.halfUz());
                putFloat3(result, 8, light.halfVx(), light.halfVy(), light.halfVz());
                double crossX = light.halfUy() * light.halfVz() - light.halfUz() * light.halfVy();
                double crossY = light.halfUz() * light.halfVx() - light.halfUx() * light.halfVz();
                double crossZ = light.halfUx() * light.halfVy() - light.halfUy() * light.halfVx();
                double facing = crossX * light.normalX() + crossY * light.normalY()
                        + crossZ * light.normalZ();
                result[11] = facing < 0.0 ? -1f : 1f;
                putFloat3(result, 12, light.radianceRedCdM2(), light.radianceGreenCdM2(),
                        light.radianceBlueCdM2());
            }
            case LightDescriptor.Point light -> {
                FiniteLight.from(light, metersPerWorldUnit);
                putFloat3(result, 0, light.positionX() - originX,
                        light.positionY() - originY, light.positionZ() - originZ);
                result[3] = Float.intBitsToFloat(1);
                result[7] = (float) (light.rangeMeters() / metersPerWorldUnit);
                result[11] = -1f;
                putFloat3(result, 12, light.intensityRedCandela(),
                        light.intensityGreenCandela(), light.intensityBlueCandela());
            }
            case LightDescriptor.Spot light -> {
                FiniteLight.from(light, metersPerWorldUnit);
                putFloat3(result, 0, light.positionX() - originX,
                        light.positionY() - originY, light.positionZ() - originZ);
                result[3] = Float.intBitsToFloat(2);
                double length = Math.sqrt(light.directionX() * light.directionX()
                        + light.directionY() * light.directionY()
                        + light.directionZ() * light.directionZ());
                putFloat3(result, 4, light.directionX() / length,
                        light.directionY() / length, light.directionZ() / length);
                result[7] = (float) (light.rangeMeters() / metersPerWorldUnit);
                result[11] = (float) Math.cos(light.outerHalfAngleRadians());
                putFloat3(result, 12, light.intensityRedCandela(),
                        light.intensityGreenCandela(), light.intensityBlueCandela());
            }
            case LightDescriptor.Distant light -> {
                DistantLight canonical = DistantLight.from(light);
                result[3] = Float.intBitsToFloat(3);
                putFloat3(result, 4, canonical.directionX(), canonical.directionY(),
                        canonical.directionZ());
                result[11] = (float) Math.cos(light.angularRadiusRadians());
                putFloat3(result, 12, light.illuminanceRedLux(), light.illuminanceGreenLux(),
                        light.illuminanceBlueLux());
            }
        }
        result[15] = (float) metersPerWorldUnit;
        return result;
    }

    private static void writeRecord(long address, LightDescriptor descriptor,
                                    double originX, double originY, double originZ,
                                    double metersPerWorldUnit) {
        MemoryUtil.memFloatBuffer(address, RECORD_BYTES / Float.BYTES).put(
                encode(descriptor, originX, originY, originZ, metersPerWorldUnit));
    }

    private static void putFloat3(float[] target, int offset, double x, double y, double z) {
        target[offset] = (float) x;
        target[offset + 1] = (float) y;
        target[offset + 2] = (float) z;
    }

    private void ensureRing(GpuContext ctx) {
        if (ring != null) {
            return;
        }
        ring = new Slot[RING_SIZE];
        for (int i = 0; i < ring.length; i++) {
            ring[i] = new Slot(createBuffer(ctx, MIN_CAPACITY, i), MIN_CAPACITY);
        }
    }

    private void ensureCapacity(GpuContext ctx, Slot slot, int count) {
        if (count <= slot.capacity) {
            return;
        }
        int capacity = capacityForCount(count);
        slot.buffer.destroy();
        slot.buffer = createBuffer(ctx, capacity, cursor);
        slot.capacity = capacity;
    }

    static int capacityForCount(int count) {
        int capacity = MIN_CAPACITY;
        while (capacity < count) {
            capacity = Math.multiplyExact(capacity, 2);
        }
        return capacity;
    }

    private static GpuBuffer createBuffer(GpuContext ctx, int capacity, int slotIndex) {
        return ctx.createBuffer(Math.multiplyExact((long) capacity, RECORD_BYTES),
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true,
                "provider lights " + slotIndex);
    }

    /** Called after the device is idle. */
    public void destroy() {
        if (ring == null) {
            return;
        }
        for (Slot slot : ring) {
            slot.buffer.destroy();
        }
        ring = null;
        cursor = -1;
    }

    public static final class Frame {
        private static final Frame EMPTY = new Frame(0L, 0, null);
        private final long bufferAddress;
        private final int lightCount;
        private final Slot slot;

        private Frame(long bufferAddress, int lightCount, Slot slot) {
            this.bufferAddress = bufferAddress;
            this.lightCount = lightCount;
            this.slot = slot;
        }

        public long bufferAddress() {
            return bufferAddress;
        }

        public int lightCount() {
            return lightCount;
        }
    }

    private static final class Slot {
        private GpuBuffer buffer;
        private int capacity;
        private final RtGpuExecutor.TrackedGraphicsUse graphicsUse =
                new RtGpuExecutor.TrackedGraphicsUse();

        private Slot(GpuBuffer buffer, int capacity) {
            this.buffer = buffer;
            this.capacity = capacity;
        }
    }
}
