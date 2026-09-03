package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Acceleration-structure builds, transfers, and extension compute must reach the reserved queue the same
 * way. A submission entry point beyond the public contract would let internal callers take ordering or
 * cleanup guarantees an extension cannot ask for, which is how the cross-queue coupling grew last time.
 */
final class GpuExecutorSubmissionPathTest {
    @Test
    void theExecutorSubmitsOnlyThroughTheComputeQueueContract() {
        List<String> contract = publicSubmitSignatures(GpuComputeQueue.class);

        assertEquals(1, contract.size(), "the compute-queue contract should declare one submission");
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
