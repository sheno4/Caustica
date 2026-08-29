package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

class RtGpuExecutorGraphicsWaitTest {
    @Test
    void batchesWaitForTheirLatestSourceGraphicsUse() {
        assertEquals(9L, RtGpuExecutor.maxGraphicsWait(List.of(0L, 9L, 4L)));
    }

    @Test
    void sourceGraphicsWaitCoversArbitraryRecorderCommands() {
        assertEquals(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, RtGpuExecutor.ASYNC_RECORDER_WAIT_STAGES);
    }
}
