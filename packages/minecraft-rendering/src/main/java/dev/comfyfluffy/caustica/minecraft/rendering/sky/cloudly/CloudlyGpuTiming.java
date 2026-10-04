package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.vulkan.VK10.*;

/** Cloud dispatch timestamps are read only when available, without waiting on a frame or reading an image. */
final class CloudlyGpuTiming implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(CloudlyGpuTiming.class);
    private static final int SLOTS = 16;
    private final GpuDevice gpu;
    private final ResourceFactory resources;
    private final long pool, validMask;
    private final double period;
    private final ResourceOwner owner;
    private final boolean[] pending = new boolean[SLOTS];
    private final AtomicBoolean[] completed = new AtomicBoolean[SLOTS];
    private double totalMs, maximumMs, lastReport;
    private int measurements;

    CloudlyGpuTiming(GpuDevice gpu, ResourceFactory resources) {
        this.gpu = gpu;
        this.resources = resources;
        for (int slot = 0; slot < SLOTS; slot++) completed[slot] = new AtomicBoolean(true);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var physical = gpu.vk().getPhysicalDevice();
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, properties);
            period = properties.limits().timestampPeriod();
            var count = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
            var queues = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, queues);
            int bits = 64;
            for (var queue : queues) if ((queue.queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) bits = Math.min(bits, queue.timestampValidBits());
            if (bits == 0 || !properties.limits().timestampComputeAndGraphics()) {
                pool = 0; validMask = 0; owner = null;
                return;
            }
            validMask = bits == 64 ? -1L : (1L << bits) - 1;
            var info = VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(SLOTS * 2);
            var result = stack.longs(0);
            int status = vkCreateQueryPool(gpu.vk(), info, null, result);
            if (status != VK_SUCCESS) throw new IllegalStateException("Unable to create Cloudly GPU timestamp pool: " + status);
            pool = result.get(0);
            try { owner = resources.create(() -> vkDestroyQueryPool(gpu.vk(), pool, null)); }
            catch (RuntimeException | Error failure) { vkDestroyQueryPool(gpu.vk(), pool, null); throw failure; }
        }
    }

    int begin(PassFrame frame, int width, int height) {
        if (pool == 0) return -1;
        int slot = (int)(frame.frameIndex() % SLOTS);
        if (!completed[slot].get()) return -1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (pending[slot]) {
                var values = stack.callocLong(4);
                int result = vkGetQueryPoolResults(gpu.vk(), pool, slot * 2, 2, values, 16,
                        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
                if (result == VK_NOT_READY || values.get(1) == 0 || values.get(3) == 0) return -1;
                if (result != VK_SUCCESS) throw new IllegalStateException("Cloudly GPU timestamp read failed: " + result);
                double ms = ((values.get(2) - values.get(0)) & validMask) * period / 1_000_000;
                totalMs += ms; maximumMs = Math.max(maximumMs, ms); measurements++;
                if (frame.timeSeconds() - lastReport >= 5) {
                    LOGGER.info("Cloudly GPU dispatch: average={} ms, max={} ms, measuredFrames={}, output={}x{}",
                            Math.round(totalMs / measurements * 100) / 100d, Math.round(maximumMs * 100) / 100d,
                            measurements, width, height);
                    totalMs = 0; maximumMs = 0; measurements = 0; lastReport = frame.timeSeconds();
                }
            }
            frame.retain(owner);
            completed[slot].set(false);
            try (var completion = resources.create(() -> completed[slot].set(true))) { frame.retain(completion); }
            vkCmdResetQueryPool(frame.commandBuffer(), pool, slot * 2, 2);
            VK13.vkCmdWriteTimestamp2(frame.commandBuffer(), VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, pool, slot * 2);
            pending[slot] = true;
            return slot;
        }
    }

    void end(PassFrame frame, int slot) {
        if (slot >= 0) VK13.vkCmdWriteTimestamp2(frame.commandBuffer(), VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, pool, slot * 2 + 1);
    }

    @Override public void close() { if (owner != null) owner.close(); }
}
