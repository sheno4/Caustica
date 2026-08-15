package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RtGpuExecutorGraphicsWaitTest {
    @Test
    void batchesWaitForTheirLatestSourceGraphicsUse() {
        assertEquals(9L, RtGpuExecutor.maxGraphicsWait(List.of(0L, 9L, 4L)));
    }
}
