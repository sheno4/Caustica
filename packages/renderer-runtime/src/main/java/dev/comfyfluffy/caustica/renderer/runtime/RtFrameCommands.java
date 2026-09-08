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
    private final ArrayList<Stage> stages = new ArrayList<>();
    private ArrayList<RtGpuTiming.Stage> passTimings;
    private final RtGpuTiming gpuTiming;
    private final GraphicsUse graphicsUse;
    private final long frameId;

    private static final class Stage {
        final OwnedCommandBuffer commands;
        RtGpuTiming.Stage timing;

        Stage(OwnedCommandBuffer commands) { this.commands = commands; }
    }

    RtFrameCommands(VulkanDeviceContext context, RtGpuTiming gpuTiming, GraphicsUse graphicsUse, long frameId) {
        this.context = context;
        this.gpuTiming = gpuTiming;
        this.graphicsUse = graphicsUse;
        this.frameId = frameId;
    }

    VkCommandBuffer heap(String label) {
        return begin(label, true);
    }

    VkCommandBuffer external(String label) {
        return begin(label, false);
    }

    RtGpuTiming.Stage time(String label) {
        RtGpuTiming.Stage timing = gpuTiming.begin(stages.getLast().commands.commandBuffer(), frameId, label);
        if (timing == null) return null;
        if (passTimings == null) passTimings = new ArrayList<>();
        passTimings.add(timing);
        graphicsUse.whenComplete(timing::complete);
        return timing;
    }

    private VkCommandBuffer begin(String label, boolean heaps) {
        if (!stages.isEmpty()) endLastStage();
        Stage stage = new Stage(context.beginGraphicsCommands(label, heaps));
        stages.add(stage);
        // Queue order alone does not make writes visible across command buffers.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanBarriers.memoryBarrier(stage.commands.commandBuffer(), stack);
        }
        RtGpuTiming.Stage timing = gpuTiming.begin(stage.commands.commandBuffer(), frameId, label);
        stage.timing = timing;
        if (timing != null) graphicsUse.whenComplete(timing::complete);
        return stage.commands.commandBuffer();
    }

    private void endLastStage() {
        Stage stage = stages.getLast();
        if (stage.timing != null) stage.timing.close();
        stage.commands.end();
    }

    void submit(GraphicsSubmission submission) {
        endLastStage();
        for (Stage stage : stages) {
            stage.commands.submit(submission, graphicsUse);
            if (stage.timing != null) stage.timing.submitted();
        }
        if (passTimings != null) {
            for (RtGpuTiming.Stage timing : passTimings) timing.submitted();
        }
    }

    @Override
    public void close() {
        for (Stage stage : stages) stage.commands.close();
    }
}
