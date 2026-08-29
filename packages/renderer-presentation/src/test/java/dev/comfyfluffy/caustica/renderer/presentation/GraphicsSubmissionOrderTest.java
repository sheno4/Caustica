package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
final class GraphicsSubmissionOrderTest {
    @Test
    void presentKeepsAcquireExecuteSignalOrder() {
        RecordingSubmission submission = new RecordingSubmission();

        GeneratedFrameQueue.enqueuePresent(submission, null, 11L, 12L);

        assertEquals(List.of("wait:11:0:65536", "execute", "signal:12:0:4096"), submission.calls);
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
