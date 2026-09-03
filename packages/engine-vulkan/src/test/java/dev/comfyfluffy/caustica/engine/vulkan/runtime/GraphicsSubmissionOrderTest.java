package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

final class GraphicsSubmissionOrderTest {
    @Test
    void graphicsTimelineSignalFollowsRecordedFrameWork() {
        RecordingSubmission submission = new RecordingSubmission();

        submission.execute(null);
        RtGpuExecutor.enqueueGraphicsSignal(submission, 22L, 4L);

        assertEquals(List.of(
                "execute",
                "signal:22:4:" + VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT), submission.calls);
    }

    /**
     * Compute output reaches graphics through host completion, never a cross-queue wait. A wait enqueued
     * here would also restore the present-time transitive signal dependency that forced the render thread
     * to block on the executor thread's submission progress.
     */
    @Test
    void executorNeverWaitsOnAGraphicsSubmission() throws IOException {
        String source = Files.readString(executorSource());

        assertTrue(source.contains("signalSemaphore"), "graphics-use signal is still enqueued");
        assertFalse(source.contains("waitSemaphore"),
                "RtGpuExecutor must not enqueue a compute-timeline wait on a graphics submission");
    }

    private static Path executorSource() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate != null) {
            Path source = candidate.resolve("packages/engine-vulkan/src/main/java/dev/comfyfluffy"
                    + "/caustica/engine/vulkan/runtime/RtGpuExecutor.java");
            if (Files.isRegularFile(source)) {
                return source;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Cannot locate RtGpuExecutor.java above " + System.getProperty("user.dir"));
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
