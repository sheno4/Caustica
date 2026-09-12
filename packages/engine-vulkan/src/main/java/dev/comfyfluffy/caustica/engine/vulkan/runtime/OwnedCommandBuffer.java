package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import dev.comfyfluffy.caustica.engine.vulkan.GpuCrashHistory;
import org.lwjgl.vulkan.*;

/** A primary command-buffer lease that follows its accepted graphics use through completion. */
public final class OwnedCommandBuffer implements AutoCloseable {
    private final VulkanDeviceContext ctx;
    private final VkCommandBuffer commandBuffer;
    private CommandPoolCache<VkCommandBuffer>.Lease lease;
    private boolean ended;

    OwnedCommandBuffer(VulkanDeviceContext ctx, String label, boolean descriptorHeaps) {
        this(ctx, ctx.graphics().acquireCommands());
        try {
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_COMMAND_BUFFER, commandBuffer.address(), label);
            if (descriptorHeaps) ctx.bindDescriptorHeaps(commandBuffer);
            else ctx.bindConventionalDescriptors(commandBuffer);
        } catch (Throwable failure) {
            lease.fail();
            throw failure;
        }
    }

    OwnedCommandBuffer(VulkanDeviceContext ctx, CommandPoolCache<VkCommandBuffer>.Lease lease) {
        this.ctx = ctx;
        this.lease = lease;
        commandBuffer = lease.begin(0);
    }

    public VkCommandBuffer commandBuffer() {
        if (lease == null) throw new IllegalStateException("command buffer lease is closed or submitted");
        return commandBuffer;
    }

    public void end() {
        if (lease == null) throw new IllegalStateException("command buffer lease is closed or submitted");
        if (ended) return;
        try {
            ctx.checkDeviceResult(VK10.vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer(owned graphics)");
        } catch (Throwable failure) {
            lease.fail();
            throw failure;
        }
        ended = true;
    }

    /** Return the captured lease only after the host's accepted use completes. */
    public void submit(GraphicsSubmission submission, GraphicsUse use) {
        end();
        var submittedLease = lease;
        lease = null;
        try {
            ctx.importCompletedComputeWrites(submission);
            submission.execute(commandBuffer);
            use.commandsAccepted();
            use.keepAlive(submittedLease);
            GpuCrashHistory.record(GpuCrashHistory.Event.GRAPHICS_POOL_ACCEPTED,
                    submittedLease.poolHandle(), use.value(), 0, commandBuffer.address());
        } catch (Throwable failure) {
            submittedLease.fail();
            throw failure;
        }
    }

    @Override
    public void close() {
        if (lease != null) {
            lease.close();
            lease = null;
        }
    }
}
