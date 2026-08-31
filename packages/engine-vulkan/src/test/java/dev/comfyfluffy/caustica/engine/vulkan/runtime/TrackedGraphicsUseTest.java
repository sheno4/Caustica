package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A frame reservation only signals its timeline value when its commands were accepted, so a tracked
 * owner must adopt that value on the same condition. Adopting it eagerly would leave a value here that
 * the timeline never reaches, and every later reuse wait on this owner would block forever.
 */
final class TrackedGraphicsUseTest {
    @Test
    void abandonedFrameLeavesTheLastSubmittedValue() {
        RtGpuExecutor.TrackedGraphicsUse tracked = new RtGpuExecutor.TrackedGraphicsUse();

        RtGpuExecutor.GraphicsUse submitted = new RtGpuExecutor.GraphicsUse(null, 7L);
        tracked.adopt(submitted);
        submitted.commandsAccepted();
        submitted.resolveSubmission();
        assertEquals(7L, tracked.value());

        RtGpuExecutor.GraphicsUse abandoned = new RtGpuExecutor.GraphicsUse(null, 8L);
        tracked.adopt(abandoned);
        abandoned.resolveSubmission();

        assertEquals(7L, tracked.value(), "an unsubmitted frame must not raise the reuse barrier");
    }

    @Test
    void adoptionIsNotVisibleBeforeSubmission() {
        RtGpuExecutor.TrackedGraphicsUse tracked = new RtGpuExecutor.TrackedGraphicsUse();
        RtGpuExecutor.GraphicsUse recording = new RtGpuExecutor.GraphicsUse(null, 3L);

        tracked.adopt(recording);
        assertEquals(0L, tracked.value(), "a recording frame has no signalled value yet");

        recording.commandsAccepted();
        recording.resolveSubmission();
        assertEquals(3L, tracked.value());
    }

    @Test
    void retirementObservesAMarkRecordedEarlierInTheSameFrame() {
        RtGpuExecutor.TrackedGraphicsUse tracked = new RtGpuExecutor.TrackedGraphicsUse();
        RtGpuExecutor.GraphicsUse recording = new RtGpuExecutor.GraphicsUse(null, 5L);
        List<Long> retiredAt = new ArrayList<>();

        tracked.adopt(recording);
        tracked.whenMarksApplied(() -> retiredAt.add(tracked.value()));
        assertEquals(List.of(), retiredAt, "retirement waits for the frame that is still recording");

        recording.commandsAccepted();
        recording.resolveSubmission();

        assertEquals(List.of(5L), retiredAt, "retirement must not schedule against an older frame");
    }

    @Test
    void retirementRunsImmediatelyWithNoFrameRecording() {
        RtGpuExecutor.TrackedGraphicsUse tracked = new RtGpuExecutor.TrackedGraphicsUse();
        List<Long> retiredAt = new ArrayList<>();

        tracked.whenMarksApplied(() -> retiredAt.add(tracked.value()));

        assertEquals(List.of(0L), retiredAt);
    }
}
