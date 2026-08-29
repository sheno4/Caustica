package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class GpuFrameSubmissionCallbackTest {
    @Test
    void acceptedCommandsPublishCallbacksOnceInRegistrationOrder() {
        RtGpuExecutor.GraphicsUse use = new RtGpuExecutor.GraphicsUse(null, 1L);
        List<String> events = new ArrayList<>();
        use.whenSubmitted(() -> events.add("first"));
        use.whenSubmitted(() -> events.add("second"));

        use.commandsAccepted();
        use.resolveSubmission();

        assertEquals(List.of("first", "second"), events);
        assertThrows(IllegalStateException.class, use::resolveSubmission);
    }

    @Test
    void abandonedCommandsDiscardSubmissionCallbacks() {
        RtGpuExecutor.GraphicsUse use = new RtGpuExecutor.GraphicsUse(null, 1L);
        List<String> events = new ArrayList<>();
        use.whenSubmitted(() -> events.add("unexpected"));

        use.resolveSubmission(() -> events.add("unexpected signal"));

        assertEquals(List.of(), events);
        assertThrows(IllegalStateException.class, use::commandsAccepted);
        assertThrows(IllegalStateException.class, () -> use.whenSubmitted(() -> { }));
    }

    @Test
    void acceptedCommandsSignalEvenWhenPublicationCallbackFails() {
        RtGpuExecutor.GraphicsUse use = new RtGpuExecutor.GraphicsUse(null, 1L);
        List<String> events = new ArrayList<>();
        use.whenSubmitted(() -> {
            events.add("callback");
            throw new IllegalStateException("publication failed");
        });
        use.commandsAccepted();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> use.resolveSubmission(() -> events.add("signal")));

        assertEquals("publication failed", failure.getMessage());
        assertEquals(List.of("callback", "signal"), events);
        assertThrows(IllegalStateException.class,
                () -> use.resolveSubmission(() -> events.add("duplicate signal")));
        assertEquals(List.of("callback", "signal"), events);
    }
}
