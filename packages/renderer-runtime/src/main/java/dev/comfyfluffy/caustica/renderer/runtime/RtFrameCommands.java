package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsUse;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.OwnedCommandBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanBarriers;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;

/** Ordered frame stages, each with its own descriptor state and command-pool lifetime. */
final class RtFrameCommands implements AutoCloseable {
    private final VulkanDeviceContext context;
    private final ArrayList<OwnedCommandBuffer> stages = new ArrayList<>();

    RtFrameCommands(VulkanDeviceContext context) {
        this.context = context;
    }

    VkCommandBuffer heap(String label) {
        return begin(label, true);
    }

    VkCommandBuffer external(String label) {
        return begin(label, false);
    }

    private VkCommandBuffer begin(String label, boolean heaps) {
        if (!stages.isEmpty()) stages.getLast().end();
        OwnedCommandBuffer stage = context.beginGraphicsCommands(label, heaps);
        stages.add(stage);
        // Queue order alone does not make writes visible across command buffers.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanBarriers.memoryBarrier(stage.commandBuffer(), stack);
        }
        return stage.commandBuffer();
    }

    void submit(GraphicsSubmission submission, GraphicsUse use) {
        stages.getLast().end();
        for (OwnedCommandBuffer stage : stages) stage.submit(submission, use);
    }

    @Override
    public void close() {
        for (OwnedCommandBuffer stage : stages) stage.close();
    }
}
