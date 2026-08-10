package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.rt.backend.GraphicsSubmission;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;

final class GraphicsSubmissionOrderTest {
    @Test
    void presentKeepsAcquireExecuteSignalOrder() {
        RecordingSubmission submission = new RecordingSubmission();

        RtFramePresenter.enqueuePresent(submission, null, 11L, 12L);

        assertEquals(List.of("wait:11:0:65536", "execute", "signal:12:0:4096"), submission.calls);
    }

    @Test
    void graphicsTimelineDependenciesStayOutsideRecordedFrameWork() {
        RecordingSubmission submission = new RecordingSubmission();

        RtGpuExecutor.enqueueBuildWait(submission, 21L, 3L);
        submission.execute(null);
        RtGpuExecutor.enqueueGraphicsSignal(submission, 22L, 4L);

        assertEquals(List.of(
                "wait:21:3:" + (VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                        | VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR),
                "execute",
                "signal:22:4:65536"), submission.calls);
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
