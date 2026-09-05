package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** A primary command buffer whose pool follows its accepted graphics use. */
public final class OwnedCommandBuffer implements AutoCloseable {
    private final VulkanDeviceContext ctx;
    private final VkCommandBuffer commandBuffer;
    private long pool;
    private boolean ended;

    OwnedCommandBuffer(VulkanDeviceContext ctx, String label, boolean descriptorHeaps) {
        this.ctx = ctx;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                    .queueFamilyIndex(ctx.backend().graphicsQueue().familyIndex());
            var handle = stack.mallocLong(1);
            ctx.checkDeviceResult(VK10.vkCreateCommandPool(ctx.vk(), poolInfo, null, handle), "vkCreateCommandPool(" + label + ")");
            pool = handle.get(0);
            try {
                var allocate = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                        .commandPool(pool).level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
                var pointer = stack.mallocPointer(1);
                ctx.checkDeviceResult(VK10.vkAllocateCommandBuffers(ctx.vk(), allocate, pointer), "vkAllocateCommandBuffers(" + label + ")");
                commandBuffer = new VkCommandBuffer(pointer.get(0), ctx.vk());
                var begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                        .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                ctx.checkDeviceResult(VK10.vkBeginCommandBuffer(commandBuffer, begin), "vkBeginCommandBuffer(" + label + ")");
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_COMMAND_BUFFER, commandBuffer.address(), label);
                if (descriptorHeaps) ctx.bindDescriptorHeaps(commandBuffer);
                else ctx.bindConventionalDescriptors(commandBuffer);
            } catch (Throwable failure) {
                close();
                throw failure;
            }
        }
    }

    public VkCommandBuffer commandBuffer() { return commandBuffer; }

    public void end() {
        if (ended) return;
        ctx.checkDeviceResult(VK10.vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer(owned graphics)");
        ended = true;
    }

    /** Transfer pool ownership only after the host accepts this command buffer. */
    public void submit(GraphicsSubmission submission, GraphicsUse use) {
        end();
        ctx.importCompletedComputeWrites(submission);
        submission.execute(commandBuffer);
        long submittedPool = pool;
        pool = 0L;
        use.commandsAccepted();
        use.keepAlive(() -> VK10.vkDestroyCommandPool(ctx.vk(), submittedPool, null));
    }

    @Override
    public void close() {
        if (pool != 0L) {
            VK10.vkDestroyCommandPool(ctx.vk(), pool, null);
            pool = 0L;
        }
    }
}
