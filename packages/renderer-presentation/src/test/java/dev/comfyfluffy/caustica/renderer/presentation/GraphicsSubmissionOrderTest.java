package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT;
final class GraphicsSubmissionOrderTest {
    @Test
    void presentKeepsAcquireExecuteSignalOrder() {
        RecordingSubmission submission = new RecordingSubmission();

        GeneratedFrameQueue.enqueuePresent(submission, null, 11L, 12L);

        assertEquals(List.of("wait", "execute", "signal"), submission.calls);
        assertEquals(11L, submission.waitSemaphore);
        assertEquals(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, submission.waitStageMask);
        assertEquals(12L, submission.signalSemaphore);
        assertEquals(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, submission.signalStageMask);
    }

    private static final class RecordingSubmission implements GraphicsSubmission {
        private final List<String> calls = new ArrayList<>();
        private long waitSemaphore;
        private long waitStageMask;
        private long signalSemaphore;
        private long signalStageMask;

        @Override
        public VkCommandBuffer beginTransientCommandBuffer() {
            calls.add("begin");
            return null;
        }

        @Override
        public void waitSemaphore(long semaphore, long value, long stageMask) {
            calls.add("wait");
            waitSemaphore = semaphore;
            waitStageMask = stageMask;
            assertEquals(0L, value);
        }

        @Override
        public void execute(VkCommandBuffer commandBuffer) {
            calls.add("execute");
        }

        @Override
        public void signalSemaphore(long semaphore, long value, long stageMask) {
            calls.add("signal");
            signalSemaphore = semaphore;
            signalStageMask = stageMask;
            assertEquals(0L, value);
        }
    }
}
