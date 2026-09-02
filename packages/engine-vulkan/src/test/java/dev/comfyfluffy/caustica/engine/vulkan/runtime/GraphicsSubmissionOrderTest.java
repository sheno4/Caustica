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
    void graphicsTimelineDependenciesStayOutsideRecordedFrameWork() {
        RecordingSubmission submission = new RecordingSubmission();

        RtGpuExecutor.enqueueBuildWait(submission, 21L, 3L);
        submission.execute(null);
        RtGpuExecutor.enqueueGraphicsSignal(submission, 22L, 4L);

        assertEquals(List.of(
                "wait:21:3:" + VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                "execute",
                "signal:22:4:" + VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT), submission.calls);
    }

    @Test
    void publishedComputeWaitCoversEveryGraphicsConsumerStage() {
        RecordingSubmission submission = new RecordingSubmission();

        RtGpuExecutor.enqueueBuildWait(submission, 31L, 7L);

        assertEquals(List.of("wait:31:7:" + VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT), submission.calls);
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
