package dev.comfyfluffy.caustica.minecraft.client.terrain;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtWorkerPoolTest {
    @Test
    void shutdownCancelsQueuedWorkJoinsRunningWorkAndAllowsRestart() throws Exception {
        RtWorkerPool workers = new RtWorkerPool(1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        workers.submit(() -> {
            running.countDown();
            awaitUninterruptibly(release);
            finished.countDown();
        });
        assertTrue(running.await(2, TimeUnit.SECONDS));
        workers.submit(() -> { }, cancelled::countDown);

        Thread shutdown = new Thread(workers::shutdown);
        shutdown.setDaemon(true);
        shutdown.start();
        try {
            assertTrue(cancelled.await(2, TimeUnit.SECONDS));
            assertTrue(shutdown.isAlive());
        } finally {
            release.countDown();
            shutdown.join(2_000L);
        }

        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertFalse(shutdown.isAlive());
        CountDownLatch restarted = new CountDownLatch(1);
        workers.submit(restarted::countDown);
        assertTrue(restarted.await(2, TimeUnit.SECONDS));
        assertDoesNotThrow(workers::shutdown);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
