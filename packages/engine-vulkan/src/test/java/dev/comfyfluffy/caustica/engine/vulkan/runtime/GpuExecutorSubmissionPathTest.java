package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GpuExecutorSubmissionPathTest {
    @Test
    void theExecutorSubmitsOnlyThroughTheComputeQueueContract() {
        List<String> contract = publicSubmitSignatures(GpuComputeQueue.class);

        assertEquals(2, contract.size(), "submission accepts explicit ownership or callback-owned dependencies");
        assertEquals(contract, publicSubmitSignatures(RtGpuExecutor.class),
                "RtGpuExecutor must not expose a submission beyond GpuComputeQueue");
    }

    private static List<String> publicSubmitSignatures(Class<?> type) {
        return Arrays.stream(type.getMethods())
                .filter(method -> method.getName().equals("submit"))
                .map(method -> Arrays.stream(method.getParameterTypes())
                        .map(Class::getSimpleName).collect(Collectors.joining(",", "submit(", ")")))
                .sorted().toList();
    }
}
