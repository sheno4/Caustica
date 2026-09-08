package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

final class GpuFrameSubmissionCallbackTest {
    @Test
    void sharedCallbackFailureStillResolvesRetirementAndSignalsAcceptedCommands() {
        var use = new GraphicsUse(null, 1L);
        var failure = new IllegalStateException("shared failure");
        List<String> events = new ArrayList<>();
        List<Runnable> completions = new ArrayList<>();
        use.whenSubmitted(() -> { events.add("submitted first"); throw failure; });
        use.whenSubmitted(() -> { events.add("submitted second"); throw failure; });
        use.whenSubmitted(() -> events.add("submitted last"));
        use.whenResolved(() -> { events.add("resolved first"); throw failure; });
        use.whenResolved(() -> { events.add("resolved second"); throw failure; });
        use.whenResolved(() -> events.add("resolved last"));
        use.keepAlive(() -> events.add("released"));
        use.commandsAccepted();

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> use.resolveSubmission(() -> { events.add("signal"); throw failure; }, completions::add)));

        assertEquals(List.of("submitted first", "submitted second", "submitted last",
                "resolved first", "resolved second", "resolved last", "signal"), events);
        assertEquals(1, completions.size());
        completions.getFirst().run();
        assertEquals("released", events.getLast());
        assertEquals(0, failure.getSuppressed().length);
        assertThrows(IllegalStateException.class, use::resolveSubmission);
    }

    @Test
    void sharedKeepAliveFailureStillReleasesEveryLease() {
        var use = new GraphicsUse(null, 1L);
        var failure = new IllegalStateException("shared release failure");
        List<Integer> released = new ArrayList<>();
        List<Runnable> completions = new ArrayList<>();
        use.keepAlive(() -> { released.add(1); throw failure; });
        use.keepAlive(() -> { released.add(2); throw failure; });
        use.keepAlive(() -> released.add(3));
        use.commandsAccepted();
        use.resolveSubmission(() -> { }, completions::add);

        assertSame(failure, assertThrows(IllegalStateException.class, completions.getFirst()::run));
        assertEquals(List.of(1, 2, 3), released);
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void severalCommandBuffersShareOneCompletionReservation() {
        var use = new GraphicsUse(null, 1L);
        var released = new AtomicInteger();
        var submitted = new AtomicInteger();
        List<Runnable> completion = new ArrayList<>();
        use.whenSubmitted(submitted::incrementAndGet);
        use.commandsAccepted();
        use.keepAlive(released::incrementAndGet);
        use.commandsAccepted();
        use.keepAlive(released::incrementAndGet);
        use.resolveSubmission(() -> {}, completion::add);
        assertEquals(1, submitted.get());
        assertEquals(0, released.get());
        completion.getFirst().run();
        assertEquals(2, released.get());
    }

    @Test
    void acceptedCommandsPublishCallbacksOnceInRegistrationOrder() {
        GraphicsUse use = new GraphicsUse(null, 1L);
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
        GraphicsUse use = new GraphicsUse(null, 1L);
        List<String> events = new ArrayList<>();
        use.whenSubmitted(() -> events.add("unexpected"));

        use.resolveSubmission(() -> events.add("unexpected signal"));

        assertEquals(List.of(), events);
        assertThrows(IllegalStateException.class, use::commandsAccepted);
        assertThrows(IllegalStateException.class, () -> use.whenSubmitted(() -> { }));
    }

    @Test
    void acceptedCommandsSignalEvenWhenPublicationCallbackFails() {
        GraphicsUse use = new GraphicsUse(null, 1L);
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

    @Test
    void acceptedCommandsTransferKeepAliveToTimelineCompletion() {
        GraphicsUse use = new GraphicsUse(null, 1L);
        AtomicInteger releases = new AtomicInteger();
        List<Runnable> completions = new ArrayList<>();
        use.keepAlive(releases::incrementAndGet);
        use.commandsAccepted();

        use.resolveSubmission(() -> { }, completions::add);

        assertEquals(0, releases.get());
        assertEquals(1, completions.size());
        completions.getFirst().run();
        assertEquals(1, releases.get());
    }

    @Test
    void abandonedCommandsReleaseKeepAliveImmediately() {
        GraphicsUse use = new GraphicsUse(null, 1L);
        AtomicInteger releases = new AtomicInteger();
        List<Runnable> completions = new ArrayList<>();
        use.keepAlive(releases::incrementAndGet);

        use.resolveSubmission(() -> { }, completions::add);

        assertEquals(1, releases.get());
        assertEquals(List.of(), completions);
    }

    @Test
    void keepAliveReleaseFailureDoesNotSkipOtherReleases() {
        GraphicsUse use = new GraphicsUse(null, 1L);
        AtomicInteger releases = new AtomicInteger();
        List<Runnable> completions = new ArrayList<>();
        use.keepAlive(() -> { throw new Exception("first"); });
        use.keepAlive(releases::incrementAndGet);
        use.commandsAccepted();
        use.resolveSubmission(() -> { }, completions::add);

        IllegalStateException failure = assertThrows(IllegalStateException.class, completions.getFirst()::run);

        assertEquals("graphics keep-alive release failed", failure.getMessage());
        assertEquals(1, releases.get());
    }

    @Test
    void acceptedCompletionCallbacksRunAfterTimelineInRegistrationOrder() {
        GraphicsUse use = new GraphicsUse(null, 1L);
        List<String> events = new ArrayList<>();
        List<Runnable> completions = new ArrayList<>();
        use.whenComplete(() -> events.add("first"));
        use.whenComplete(() -> events.add("second"));
        use.commandsAccepted();

        use.resolveSubmission(() -> events.add("signal"), completions::add);

        assertEquals(List.of("signal"), events);
        assertEquals(1, completions.size());
        completions.getFirst().run();
        assertEquals(List.of("signal", "first", "second"), events);
    }

    @Test
    void abandonedCompletionCallbacksRunDuringResolutionInRegistrationOrder() {
        GraphicsUse use = new GraphicsUse(null, 1L);
        List<String> events = new ArrayList<>();
        List<Runnable> completions = new ArrayList<>();
        use.whenComplete(() -> events.add("first"));
        use.whenComplete(() -> events.add("second"));

        use.resolveSubmission(() -> events.add("unexpected signal"), completions::add);

        assertEquals(List.of("first", "second"), events);
        assertEquals(List.of(), completions);
    }
}
