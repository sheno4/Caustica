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
    private final ArrayList<RtGpuTiming.Stage> timings = new ArrayList<>();
    private ArrayList<RtGpuTiming.Stage> passTimings;
    private final RtGpuTiming gpuTiming;
    private final GraphicsUse graphicsUse;
    private final long frameId;

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

    interface Timing extends AutoCloseable {
        Timing DISABLED = () -> { };
        @Override void close();
    }

    Timing time(String label) {
        RtGpuTiming.Stage timing = gpuTiming.begin(stages.getLast().commandBuffer(), frameId, label);
        if (timing == null) return Timing.DISABLED;
        if (passTimings == null) passTimings = new ArrayList<>();
        passTimings.add(timing);
        graphicsUse.whenComplete(timing::complete);
        return timing::end;
    }

    private VkCommandBuffer begin(String label, boolean heaps) {
        if (!stages.isEmpty()) endLastStage();
        OwnedCommandBuffer stage = context.beginGraphicsCommands(label, heaps);
        stages.add(stage);
        // Queue order alone does not make writes visible across command buffers.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanBarriers.memoryBarrier(stage.commandBuffer(), stack);
        }
        RtGpuTiming.Stage timing = gpuTiming.begin(stage.commandBuffer(), frameId, label);
        timings.add(timing);
        if (timing != null) graphicsUse.whenComplete(timing::complete);
        return stage.commandBuffer();
    }

    private void endLastStage() {
        RtGpuTiming.Stage timing = timings.getLast();
        if (timing != null) timing.end();
        stages.getLast().end();
    }

    void submit(GraphicsSubmission submission, GraphicsUse use) {
        endLastStage();
        for (int i = 0; i < stages.size(); i++) {
            stages.get(i).submit(submission, use);
            RtGpuTiming.Stage timing = timings.get(i);
            if (timing != null) timing.submitted();
        }
        if (passTimings != null) {
            for (RtGpuTiming.Stage timing : passTimings) timing.submitted();
        }
    }

    @Override
    public void close() {
        for (OwnedCommandBuffer stage : stages) stage.close();
    }
}
