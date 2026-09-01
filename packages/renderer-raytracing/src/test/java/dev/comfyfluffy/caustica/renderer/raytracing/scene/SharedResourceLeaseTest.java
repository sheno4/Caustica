package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SharedResourceLeaseTest {
    @Test
    void disposesOnceWhenTheLastLeaseCloses() {
        Object resource = new Object();
        AtomicInteger disposals = new AtomicInteger();
        SharedResourceLease<Object> root = SharedResourceLease.owned(
                resource, ignored -> disposals.incrementAndGet());
        SharedResourceLease<Object> first = root.retain();
        SharedResourceLease<Object> second = first.retain();

        root.close();
        first.close();

        assertEquals(0, disposals.get());
        assertSame(resource, second.get());

        second.close();

        assertEquals(1, disposals.get());
    }

    @Test
    void closeIsIdempotentAndClosedLeaseCannotBeUsed() {
        AtomicInteger disposals = new AtomicInteger();
        SharedResourceLease<Object> lease = SharedResourceLease.owned(
                new Object(), ignored -> disposals.incrementAndGet());

        lease.close();
        lease.close();

        assertEquals(1, disposals.get());
        assertThrows(IllegalStateException.class, lease::get);
        assertThrows(IllegalStateException.class, lease::retain);
    }

    @Test
    void disposerReceivesTheOwnedValue() {
        Object resource = new Object();
        List<Object> disposed = new ArrayList<>();
        SharedResourceLease<Object> lease = SharedResourceLease.owned(resource, disposed::add);

        lease.close();

        assertEquals(List.of(resource), disposed);
    }

    @Test
    void concurrentLastReleasesDisposeExactlyOnce() throws InterruptedException {
        int leaseCount = 32;
        AtomicInteger disposals = new AtomicInteger();
        SharedResourceLease<Object> root = SharedResourceLease.owned(
                new Object(), ignored -> disposals.incrementAndGet());
        List<SharedResourceLease<Object>> leases = new ArrayList<>();
        for (int index = 0; index < leaseCount; index++) {
            leases.add(root.retain());
        }
        root.close();

        CountDownLatch ready = new CountDownLatch(leaseCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (SharedResourceLease<Object> lease : leases) {
            Thread thread = Thread.ofPlatform().start(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                lease.close();
            });
            threads.add(thread);
        }

        ready.await();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(1, disposals.get());
    }
}
