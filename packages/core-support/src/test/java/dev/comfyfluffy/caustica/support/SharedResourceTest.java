package dev.comfyfluffy.caustica.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SharedResourceTest {
    @Test
    void referenceCanAcquireThroughAnotherOwnerButCannotResurrectDisposedValue() {
        AtomicInteger disposals = new AtomicInteger();
        var owner = SharedResource.owned(new Object(), ignored -> disposals.incrementAndGet());
        var reference = owner.reference();
        var survivor = owner.retain();
        assertSame(reference, survivor.reference());
        owner.close();
        try (var acquired = reference.retain()) {
            survivor.close();
            assertEquals(true, reference.isAlive());
            assertEquals(0, disposals.get());
        }
        assertEquals(false, reference.isAlive());
        assertEquals(1, disposals.get());
        assertThrows(IllegalStateException.class, reference::retain);
    }

    @Test
    void referenceAcquisitionRacingFinalReleaseEitherOwnsValueOrFails() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int attempt = 0; attempt < 100; attempt++) {
                AtomicInteger disposals = new AtomicInteger();
                var owner = SharedResource.owned(new Object(), ignored -> disposals.incrementAndGet());
                var reference = owner.reference();
                var start = new CountDownLatch(1);
                var release = executor.submit(() -> {
                    start.await();
                    owner.close();
                    return null;
                });
                var acquire = executor.submit(() -> {
                    start.await();
                    SharedResource<Object> claim;
                    try { claim = reference.retain(); }
                    catch (IllegalStateException released) { return null; }
                    try (claim) {
                        assertEquals(0, disposals.get());
                        claim.get();
                    }
                    return null;
                });
                start.countDown();
                release.get(10, TimeUnit.SECONDS);
                acquire.get(10, TimeUnit.SECONDS);
                assertEquals(1, disposals.get());
                assertThrows(IllegalStateException.class, reference::retain);
            }
        }
    }

    @Test
    void disposesOwnedValueWhenLastHandleCloses() {
        Object value = new Object();
        List<Object> disposed = new ArrayList<>();
        SharedResource<Object> owner = SharedResource.owned(value, disposed::add);
        SharedResource<Object> first = owner.retain();
        SharedResource<Object> second = first.retain();

        owner.close();
        first.close();

        assertEquals(List.of(), disposed);
        assertSame(value, second.get());

        second.close();
        assertEquals(List.of(value), disposed);
    }

    @Test
    void closeIsIdempotentAndClosedHandleCannotBeUsed() {
        AtomicInteger disposals = new AtomicInteger();
        SharedResource<Object> resource = SharedResource.owned(
                new Object(), ignored -> disposals.incrementAndGet());

        resource.close();
        resource.close();

        assertEquals(1, disposals.get());
        assertThrows(IllegalStateException.class, resource::get);
        assertThrows(IllegalStateException.class, resource::retain);
    }

    @Test
    void concurrentLastClosesDisposeExactlyOnce() throws InterruptedException {
        int retainedCount = 32;
        AtomicInteger disposals = new AtomicInteger();
        SharedResource<Object> owner = SharedResource.owned(
                new Object(), ignored -> disposals.incrementAndGet());
        List<SharedResource<Object>> retained = new ArrayList<>();
        for (int index = 0; index < retainedCount; index++) retained.add(owner.retain());
        owner.close();

        CountDownLatch ready = new CountDownLatch(retainedCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (SharedResource<Object> resource : retained) {
            threads.add(Thread.ofPlatform().start(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                resource.close();
            }));
        }

        ready.await();
        start.countDown();
        for (Thread thread : threads) thread.join();

        assertEquals(1, disposals.get());
    }
}
