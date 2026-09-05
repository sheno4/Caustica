package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

final class GraphicsSubmissionOrderTest {
    @Test
    void graphicsTimelineSignalFollowsRecordedFrameWork() {
        RecordingSubmission submission = new RecordingSubmission();

        submission.execute(null);
        GraphicsQueue.enqueueGraphicsSignal(submission, 22L, 4L);

        assertEquals(List.of(
                "execute",
                "signal:22:4:" + VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT), submission.calls);
    }

    @Test
    void completedComputeGetsADeviceMemoryDependency() {
        RecordingSubmission submission = new RecordingSubmission();
        VulkanDeviceContext.enqueueCompletedComputeWait(submission, 19L, 7L);
        assertEquals(List.of("wait:19:7:" + VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT), submission.calls);
    }

    @Test
    void noCompletedComputeNeedsNoWait() {
        RecordingSubmission submission = new RecordingSubmission();
        VulkanDeviceContext.enqueueCompletedComputeWait(submission, 19L, 0L);
        assertEquals(List.of(), submission.calls);
    }

    private static final class RecordingSubmission implements GraphicsSubmission {
        private final List<String> calls = new ArrayList<>();

        @Override
        public VkCommandBuffer beginTransientCommandBuffer() {
            calls.add("begin");
            return null;
        }

        @Override
        public void waitSemaphore(long semaphore, long value, long stageMask) {
            calls.add("wait:" + semaphore + ":" + value + ":" + stageMask);
        }

        @Override
        public void execute(VkCommandBuffer commandBuffer) {
            calls.add("execute");
        }

        @Override
        public void signalSemaphore(long semaphore, long value, long stageMask) {
            calls.add("signal:" + semaphore + ":" + value + ":" + stageMask);
        }
    }
}
