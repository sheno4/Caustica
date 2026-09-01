package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SharedResourceOwnerTest {
    @Test
    void disposesOnceWhenTheLastReferenceCloses() {
        Object resource = new Object();
        AtomicInteger disposals = new AtomicInteger();
        SharedResourceOwner<Object> owner = new SharedResourceOwner<>(resource, ignored -> disposals.incrementAndGet());
        SharedResourceLease<Object> first = owner.retain();
        SharedResourceLease<Object> second = first.retain();

        owner.close();
        first.close();

        assertEquals(0, disposals.get());
        assertSame(resource, second.get());

        second.close();

        assertEquals(1, disposals.get());
    }

    @Test
    void transferMovesAReferenceWithoutExtendingItsLifetime() {
        AtomicInteger disposals = new AtomicInteger();
        SharedResourceOwner<Object> owner = new SharedResourceOwner<>(new Object(), ignored -> disposals.incrementAndGet());

        SharedResourceLease<Object> lease = owner.transfer();

        assertThrows(IllegalStateException.class, owner::get);
        assertThrows(IllegalStateException.class, owner::retain);
        assertThrows(IllegalStateException.class, owner::close);
        assertEquals(0, disposals.get());

        SharedResourceLease<Object> moved = lease.transfer();

        assertThrows(IllegalStateException.class, lease::get);
        assertThrows(IllegalStateException.class, lease::retain);
        assertThrows(IllegalStateException.class, lease::close);

        moved.close();

        assertEquals(1, disposals.get());
    }

    @Test
    void doubleCloseIsRejected() {
        SharedResourceOwner<Object> owner = new SharedResourceOwner<>(new Object(), ignored -> { });
        SharedResourceLease<Object> lease = owner.retain();

        owner.close();
        lease.close();

        assertThrows(IllegalStateException.class, owner::close);
        assertThrows(IllegalStateException.class, lease::close);
    }

    @Test
    void concurrentLastReleasesDisposeExactlyOnce() throws InterruptedException {
        int leaseCount = 32;
        AtomicInteger disposals = new AtomicInteger();
        SharedResourceOwner<Object> owner = new SharedResourceOwner<>(new Object(), ignored -> disposals.incrementAndGet());
        List<SharedResourceLease<Object>> leases = new ArrayList<>();
        for (int index = 0; index < leaseCount; index++) {
            leases.add(owner.retain());
        }
        owner.close();

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
