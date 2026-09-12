package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import dev.comfyfluffy.caustica.engine.vulkan.GpuCrashHistory;
import static dev.comfyfluffy.caustica.engine.vulkan.GpuCrashHistory.Event.*;

/** Native pool operations run exclusively on the cache's recording thread until shutdown. */
final class VulkanCommandPoolBackend implements CommandPoolCache.Backend<VkCommandBuffer> {
    private final VulkanDeviceContext ctx;
    private final int family;
    private final String label;

    VulkanCommandPoolBackend(VulkanDeviceContext ctx, int family, String label) {
        this.ctx = ctx;
        this.family = family;
        this.label = label;
    }

    @Override public long createPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT).queueFamilyIndex(family);
            var out = stack.mallocLong(1);
            ctx.checkDeviceResult(VK10.vkCreateCommandPool(ctx.vk(), info, null, out), "vkCreateCommandPool(" + label + ")");
            GpuCrashHistory.record(POOL_CREATE, out.get(0), 0, 0, family);
            return out.get(0);
        }
    }

    @Override public List<VkCommandBuffer> allocate(long pool, int count) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(pool).level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(count);
            var out = stack.mallocPointer(count);
            ctx.checkDeviceResult(VK10.vkAllocateCommandBuffers(ctx.vk(), info, out), "vkAllocateCommandBuffers(" + label + ")");
            var commands = new ArrayList<VkCommandBuffer>(count);
            for (int i = 0; i < count; i++) commands.add(new VkCommandBuffer(out.get(i), ctx.vk()));
            GpuCrashHistory.record(POOL_ALLOCATE, pool, 0, 0, count);
            return commands;
        }
    }

    @Override public void reset(long pool) {
        int result = VK10.vkResetCommandPool(ctx.vk(), pool, 0);
        GpuCrashHistory.record(POOL_RESET, pool, 0, 0, result);
        ctx.checkDeviceResult(result, "vkResetCommandPool(" + label + ")");
    }

    @Override public void begin(VkCommandBuffer command) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            ctx.checkDeviceResult(VK10.vkBeginCommandBuffer(command, info), "vkBeginCommandBuffer(" + label + ")");
        }
    }

    @Override public void destroy(long pool) {
        GpuCrashHistory.record(POOL_DESTROY, pool, 0, 0, 0);
        VK10.vkDestroyCommandPool(ctx.vk(), pool, null);
    }
}
