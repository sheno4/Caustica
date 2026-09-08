package dev.comfyfluffy.caustica.engine.compute;

import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeJob;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ComputeQueueSessionTest {
    @Test
    void drainWaitsForAlreadyRunningWorkAndDeliversItsCompletion() throws Exception {
        var cancelled = new CountDownLatch(1);
        var complete = new AtomicReference<Consumer<? super GpuComputeCompletion>>();
        GpuComputeQueue backend = new GpuComputeQueue() {
            @Override public GpuComputeJob submit(Consumer<? super VkCommandBuffer> recorder,
                    Consumer<? super GpuComputeCompletion> completion) {
                complete.set(completion);
                return cancelled::countDown;
            }
            @Override public int[] sharedQueueFamilyIndices() { return new int[]{0}; }
        };
        var session = new ComputeQueueSession(backend, failure -> { throw new AssertionError(failure); });
        var queue = session.openChannel();
        var delivered = new AtomicInteger();
        queue.submit(command -> {}, result -> delivered.incrementAndGet());
        var drained = CompletableFuture.runAsync(queue::drain);
        try {
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            assertFalse(drained.isDone());
        } finally {
            complete.get().accept(new GpuComputeCompletion.Succeeded());
            drained.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, delivered.get());
        queue.closeChannel();
        session.close();
    }

    @Test
    void ownedDependenciesSurviveUntilPublicCompletionReturns() {
        Backend backend = new Backend();
        ComputeQueueSession session = new ComputeQueueSession(backend, failure -> { throw new AssertionError(failure); });
        var queue = session.openChannel();
        List<String> events = new ArrayList<>();
        queue.submit(command -> {}, List.of(() -> events.add("release")), result -> events.add("callback"));
        backend.succeed(0);
        assertEquals(List.of(), events);
        session.progress();
        assertEquals(List.of("callback", "release"), events);
        queue.drain();
        queue.closeChannel();
        session.close();
    }

    @Test
    void acceptedCompletionBecomesVisibleOnlyThroughSessionProgress() {
        Backend backend = new Backend();
        List<Throwable> failures = new ArrayList<>();
        ComputeQueueSession session = new ComputeQueueSession(backend, failures::add);
        ComputeQueueSession.ComputeContributionQueue queue = session.openChannel();
        AtomicInteger recordings = new AtomicInteger();
        AtomicReference<GpuComputeCompletion> result = new AtomicReference<>();

        queue.submit(commandBuffer -> recordings.incrementAndGet(), result::set);
        backend.succeed(0);

        assertEquals(1, recordings.get());
        assertNull(result.get());
        session.progress();
        assertInstanceOf(GpuComputeCompletion.Succeeded.class, result.get());
        assertEquals(List.of(), failures);
        assertArrayEquals(new int[] { 2, 5 }, queue.sharedQueueFamilyIndices());

        queue.quiesce();
        assertThrows(IllegalStateException.class,
                () -> queue.submit(commandBuffer -> { }, completion -> { }));
        queue.drain();
        queue.closeChannel();
        session.close();
    }

    @Test
    void drainingCancelsQueuedWorkAndDeliversItsTerminalCallback() {
        Backend backend = new Backend();
        ComputeQueueSession session = new ComputeQueueSession(backend, failure -> {
            throw new AssertionError(failure);
        });
        ComputeQueueSession.ComputeContributionQueue queue = session.openChannel();
        AtomicReference<GpuComputeCompletion> result = new AtomicReference<>();

        queue.submit(commandBuffer -> { }, result::set);
        queue.drain();

        assertInstanceOf(GpuComputeCompletion.Cancelled.class, result.get());
        assertEquals(0, backend.recordings.get());
        queue.closeChannel();
        session.close();
    }

    @Test
    void callbackFailureIsReportedWithoutKeepingTheOwnerAlive() {
        Backend backend = new Backend();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        ComputeQueueSession session = new ComputeQueueSession(backend, reported::set);
        ComputeQueueSession.ComputeContributionQueue queue = session.openChannel();
        IllegalStateException callbackFailure = new IllegalStateException("callback failed");

        queue.submit(commandBuffer -> { }, completion -> {
            throw callbackFailure;
        });
        backend.succeed(0);
        session.progress();

        assertSame(callbackFailure, reported.get());
        queue.drain();
        queue.closeChannel();
        session.close();
    }

    @Test
    void oneContributionAcceptsConcurrentProducerSubmissions() {
        Backend backend = new Backend();
        ComputeQueueSession session = new ComputeQueueSession(backend, failure -> {
            throw new AssertionError(failure);
        });
        ComputeQueueSession.ComputeContributionQueue queue = session.openChannel();
        AtomicInteger completed = new AtomicInteger();

        IntStream.range(0, 64).parallel().forEach(index ->
                queue.submit(commandBuffer -> { }, completion -> completed.incrementAndGet()));
        IntStream.range(0, 64).forEach(backend::succeed);
        session.progress();

        assertEquals(64, completed.get());
        queue.drain();
        queue.closeChannel();
        session.close();
    }

    private static final class Backend implements GpuComputeQueue {
        private final List<Pending> pending = new ArrayList<>();
        private final AtomicInteger recordings = new AtomicInteger();

        @Override
        public synchronized GpuComputeJob submit(Consumer<? super VkCommandBuffer> recorder,
                                                 Consumer<? super GpuComputeCompletion> completion) {
            Pending job = new Pending(recorder, completion);
            pending.add(job);
            return job;
        }

        @Override public int[] sharedQueueFamilyIndices() { return new int[] { 2, 5 }; }

        void succeed(int index) {
            Pending job = pending.get(index);
            recordings.incrementAndGet();
            job.recorder.accept(null);
            job.finish(new GpuComputeCompletion.Succeeded());
        }

        private static final class Pending implements GpuComputeJob {
            private final Consumer<? super VkCommandBuffer> recorder;
            private final Consumer<? super GpuComputeCompletion> completion;
            private boolean finished;

            private Pending(Consumer<? super VkCommandBuffer> recorder,
                            Consumer<? super GpuComputeCompletion> completion) {
                this.recorder = recorder;
                this.completion = completion;
            }

            @Override public void close() {
                if (!finished) finish(new GpuComputeCompletion.Cancelled());
            }

            private void finish(GpuComputeCompletion result) {
                if (finished) throw new IllegalStateException("backend job completed twice");
                finished = true;
                completion.accept(result);
            }
        }
    }
}
