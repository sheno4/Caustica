package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class RtScenePublicationTest {
    @Test void failedQueuedInputReleaseStillDrainsPreparationAndReleasesCompletedState() throws Exception {
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var discarded = new CountDownLatch(1);
        var released = new CopyOnWriteArrayList<Integer>();
        record FailingInput(int value, Runnable release) implements AutoCloseable {
            @Override public void close() { release.run(); }
        }
        try (var publication = new RtScenePublication<FailingInput, Integer>(input -> {
            if (input.value == 2) {
                entered.countDown();
                await(resume);
            }
            return SharedResource.owned(input.value, released::add);
        })) {
            publication.request(1, new FailingInput(1, () -> { }));
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while (true) {
                    try (var revision = publication.acquire()) {
                        if (revision != null) break;
                    }
                    Thread.yield();
                }
            });
            publication.request(2, new FailingInput(2, () -> { }));
            await(entered);
            publication.request(3, new FailingInput(3, () -> {
                discarded.countDown();
                throw new IllegalStateException("queued input release");
            }));
            var clearing = java.util.concurrent.CompletableFuture.runAsync(publication::clear);
            try {
                await(discarded);
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                    while (!released.contains(1)) {
                        if (clearing.isDone()) fail("clear returned before releasing completed state");
                        Thread.yield();
                    }
                });
                assertFalse(clearing.isDone());
            } finally {
                resume.countDown();
            }
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> clearing.get(10, TimeUnit.SECONDS));
            assertEquals("queued input release", failure.getCause().getMessage());
            assertTrue(released.containsAll(List.of(1, 2)));
            assertNull(publication.acquire());
        }
    }

    @Test void readersKeepTheCompleteRevisionWhileWorkerPreparesAndCoalescesReplacements() throws Exception {
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var prepared = new CopyOnWriteArrayList<Integer>();
        var released = new CopyOnWriteArrayList<Integer>();
        var inputs = new AtomicInteger();
        try (var publication = new RtScenePublication<Input, Integer>(input -> {
            prepared.add(input.value);
            if (input.value == 2) {
                entered.countDown();
                await(resume);
            }
            return SharedResource.owned(input.value, released::add);
        })) {
            publication.request(1, new Input(1, inputs));
            try (var first = awaitValue(publication, 1)) {
                publication.request(2, new Input(2, inputs));
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                try {
                    assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                        try (var frame = publication.acquire()) { assertEquals(1, frame.get()); }
                    });
                    publication.request(3, new Input(3, inputs));
                    publication.request(4, new Input(4, inputs));
                    publication.request(4, new Input(4, inputs));
                    assertEquals(3, inputs.get());
                } finally { resume.countDown(); }
                try (var latest = awaitValue(publication, 4)) {
                    assertEquals(List.of(1, 2, 4), prepared);
                    assertFalse(released.contains(1));
                    publication.clear();
                    assertNull(publication.acquire());
                    assertEquals(4, latest.get());
                }
                assertTrue(released.containsAll(List.of(2, 4)));
            }
            assertTrue(released.contains(1));
            assertEquals(5, inputs.get());
        }
    }

    @Test void lifecycleClearRejectsWorkFromThePreviousGeneration() throws Exception {
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var closed = new AtomicInteger();
        var inputs = new AtomicInteger();
        try (var publication = new RtScenePublication<Input, Integer>(input -> {
            if (input.value == 1) {
                entered.countDown();
                await(resume);
            }
            return SharedResource.owned(input.value, ignored -> closed.incrementAndGet());
        })) {
            publication.request(1, new Input(1, inputs));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            publication.request(2, new Input(2, inputs));
            var clearing = java.util.concurrent.CompletableFuture.runAsync(publication::clear);
            try {
                // Releasing the queued input proves clear has invalidated the generation before the
                // active preparation is allowed to finish. It must still wait for that active input.
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                    while (inputs.get() == 0) Thread.yield();
                });
                assertFalse(clearing.isDone());
                // Clear blocks only lifecycle callers; frame readers remain independent of preparation.
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertNull(publication.acquire()));
            } finally { resume.countDown(); }
            clearing.get(10, TimeUnit.SECONDS);
            assertNull(publication.acquire());
            assertEquals(2, inputs.get());
            assertEquals(1, closed.get());
            publication.request(1, new Input(3, inputs));
            try (var next = awaitValue(publication, 3)) { assertEquals(3, next.get()); }
        }
    }

    @Test void failedPreparationClosesItsInputAndReportsTheFailureToReaders() {
        var inputs = new AtomicInteger();
        var entered = new CountDownLatch(1);
        try (var publication = new RtScenePublication<Input, Integer>(input -> {
            entered.countDown();
            throw new IllegalArgumentException("invalid revision");
        })) {
            publication.request(1, new Input(1, inputs));
            await(entered);
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while (inputs.get() == 0) Thread.onSpinWait();
                while (true) {
                    try (var ignored = publication.acquire()) { Thread.onSpinWait(); }
                    catch (IllegalStateException failure) {
                        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
                        break;
                    }
                }
            });
            publication.clear();
            assertNull(publication.acquire());
        }
    }

    @Test void failedReplacementKeepsLastCompletedRevisionAndLaterSuccessPublishes() {
        var inputs = new AtomicInteger();
        try (var publication = new RtScenePublication<Input, Integer>(input -> {
            if (input.value == 2) throw new IllegalArgumentException("invalid replacement");
            return SharedResource.owned(input.value, ignored -> { });
        })) {
            publication.request(1, new Input(1, inputs));
            try (var first = awaitValue(publication, 1)) {
                publication.request(2, new Input(2, inputs));
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                    while (inputs.get() < 2) Thread.yield();
                });
                try (var unchanged = publication.acquire()) { assertEquals(1, unchanged.get()); }
                publication.request(3, new Input(3, inputs));
                try (var next = awaitValue(publication, 3)) { assertEquals(3, next.get()); }
                assertEquals(1, first.get());
            }
        }
    }

    private static SharedResource<Integer> awaitValue(RtScenePublication<Input, Integer> publication, int value) {
        return assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            while (true) {
                var revision = publication.acquire();
                if (revision != null) {
                    if (revision.get() == value) return revision;
                    revision.close();
                }
                Thread.yield();
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private record Input(int value, AtomicInteger closed) implements AutoCloseable {
        @Override public void close() { closed.incrementAndGet(); }
    }
}
