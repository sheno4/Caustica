package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.util.ArrayDeque;

/** Query pools are reused only after the graphics timeline releases their command buffers. */
final class RtGpuTiming implements AutoCloseable {
    private static final EventType EVENT = EventType.getEventType(GpuStageEvent.class);
    private final VulkanDeviceContext context;
    private final ArrayDeque<Long> available = new ArrayDeque<>();
    private boolean initialized;
    private boolean closed;
    private int validBits;
    private int queueFamily;
    private float periodNanos;

    RtGpuTiming(VulkanDeviceContext context) {
        this.context = context;
    }

    Stage begin(VkCommandBuffer command, long frameId, String label) {
        if (!EVENT.isEnabled()) return null;
        return beginEnabled(command, frameId, label);
    }

    private synchronized Stage beginEnabled(VkCommandBuffer command, long frameId, String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!initialized) {
                var physical = context.vk().getPhysicalDevice();
                var properties = VkPhysicalDeviceProperties.calloc(stack);
                VK10.vkGetPhysicalDeviceProperties(physical, properties);
                periodNanos = properties.limits().timestampPeriod();
                var count = stack.mallocInt(1);
                VK10.vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
                var queues = VkQueueFamilyProperties.calloc(count.get(0), stack);
                VK10.vkGetPhysicalDeviceQueueFamilyProperties(physical, count, queues);
                queueFamily = context.backend().graphicsQueue().familyIndex();
                validBits = queues.get(queueFamily).timestampValidBits();
                initialized = true;
            }
            if (validBits == 0) return null;
            long pool;
            if (available.isEmpty()) {
                var info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                        .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(2);
                var handle = stack.mallocLong(1);
                context.checkDeviceResult(VK10.vkCreateQueryPool(context.vk(), info, null, handle),
                        "vkCreateQueryPool(GPU timing)");
                pool = handle.get(0);
            } else {
                pool = available.removeFirst();
            }
            VK10.vkCmdResetQueryPool(command, pool, 0, 2);
            VK13.vkCmdWriteTimestamp2(command, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, pool, 0);
            return new Stage(command, pool, frameId, label);
        }
    }

    private synchronized void recycle(long pool) {
        if (closed) VK10.vkDestroyQueryPool(context.vk(), pool, null);
        else available.addLast(pool);
    }

    @Override
    public synchronized void close() {
        closed = true;
        while (!available.isEmpty()) VK10.vkDestroyQueryPool(context.vk(), available.removeFirst(), null);
    }

    static double elapsedNanos(long start, long end, int validBits, float periodNanos) {
        long delta = end - start;
        if (validBits < Long.SIZE) delta &= (1L << validBits) - 1;
        double ticks = delta >= 0 ? delta : (double) (delta & Long.MAX_VALUE) + 0x1.0p63;
        return ticks * periodNanos;
    }

    final class Stage {
        private final VkCommandBuffer command;
        private final long pool;
        private final long frameId;
        private final String label;
        private boolean submitted;

        private Stage(VkCommandBuffer command, long pool, long frameId, String label) {
            this.command = command;
            this.pool = pool;
            this.frameId = frameId;
            this.label = label;
        }

        void end() {
            VK13.vkCmdWriteTimestamp2(command, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, pool, 1);
        }

        void submitted() {
            submitted = true;
        }

        void complete() {
            try {
                if (!submitted || !EVENT.isEnabled()) return;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    var values = stack.mallocLong(2);
                    int result = VK10.vkGetQueryPoolResults(context.vk(), pool, 0, 2, values,
                            Long.BYTES, VK10.VK_QUERY_RESULT_64_BIT);
                    // Abandoned or partially recorded work must never make telemetry wait for a query.
                    if (result == VK10.VK_NOT_READY) return;
                    context.checkDeviceResult(result, "vkGetQueryPoolResults(GPU timing)");
                    var event = new GpuStageEvent();
                    event.frameId = frameId;
                    event.stage = label;
                    event.startTicks = values.get(0);
                    event.endTicks = values.get(1);
                    event.timestampValidBits = validBits;
                    event.queueFamily = queueFamily;
                    event.timestampPeriodNanos = periodNanos;
                    event.elapsedNanos = Math.round(elapsedNanos(event.startTicks, event.endTicks, validBits, periodNanos));
                    event.commit();
                }
            } finally {
                recycle(pool);
            }
        }
    }

    @Name("dev.comfyfluffy.caustica.GpuStage")
    @Label("Caustica GPU stage")
    @Category("Caustica")
    @Enabled(false)
    @StackTrace(false)
    static final class GpuStageEvent extends Event {
        long frameId;
        String stage;
        long startTicks;
        long endTicks;
        int timestampValidBits;
        int queueFamily;
        float timestampPeriodNanos;
        @Timespan(Timespan.NANOSECONDS)
        long elapsedNanos;
    }
}
