package dev.comfyfluffy.caustica.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SharedResourceTest {
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
