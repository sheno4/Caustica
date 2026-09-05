package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.api.vulkan.*;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

final class GpuComputeOwnershipTest {
    @Test
    void everyTerminalResultReleasesAfterNotification() {
        for (GpuComputeCompletion result : List.of(new GpuComputeCompletion.Succeeded(),
                new GpuComputeCompletion.Cancelled(), new GpuComputeCompletion.Failed(new Exception()))) {
            var queue = new Queue();
            List<String> events = new ArrayList<>();
            queue.submit(cmd -> {}, List.of(() -> events.add("release")), value -> events.add("complete"));
            assertEquals(List.of(), events);
            queue.completion.accept(result);
            assertEquals(List.of("complete", "release"), events);
        }
    }

    @Test
    void rejectionKeepsOwnershipWithCaller() {
        var queue = new Queue();
        queue.reject = true;
        List<String> events = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> queue.submit(cmd -> {},
                List.of(() -> events.add("release")), value -> events.add("complete")));
        assertEquals(List.of(), events);
    }

    private static final class Queue implements GpuComputeQueue {
        Consumer<? super GpuComputeCompletion> completion;
        boolean reject;
        public GpuComputeJob submit(Consumer<? super VkCommandBuffer> recorder,
                                    Consumer<? super GpuComputeCompletion> completion) {
            if (reject) throw new IllegalStateException("closed");
            this.completion = completion;
            return () -> {};
        }
        public int[] sharedQueueFamilyIndices() { return new int[] {0}; }
    }
}
