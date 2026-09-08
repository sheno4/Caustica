package dev.comfyfluffy.caustica.engine.resource;

import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
final class ResourceDirectoryTest {
    private final ContributionOwner producer = new ContributionOwner(1);

    @Test void rejectedFrameRetentionReleasesOnlyItsNewClaim() {
        AtomicInteger destroyed = new AtomicInteger();
        try (var directory = new ResourceDirectory(failure -> fail(failure))) {
            var owner = directory.openFactory(producer).create(destroyed::incrementAndGet);
            var frame = new ResourceOwners();
            frame.close();
            assertThrows(IllegalStateException.class, () -> frame.retain(owner));
            assertEquals(0, destroyed.get());
            owner.close();
            directory.awaitRetirements();
            assertEquals(1, destroyed.get());
        }
    }

    @Test void producerDrainDoesNotWaitForAnotherContributionsOwnership() {
        AtomicInteger destroyed = new AtomicInteger();
        try (var directory = new ResourceDirectory(failure -> fail(failure))) {
            var owner = directory.openFactory(producer).create(destroyed::incrementAndGet);
            try (var consumer = owner.retain()) {
                directory.invalidate(producer);
                directory.drain(() -> {});
                assertEquals(0, destroyed.get());
                try (var retained = consumer.retain()) {
                    assertNotSame(owner, retained);
                }
            }
            directory.awaitRetirements();
            assertEquals(1, destroyed.get());
        }
    }

    @Test void closeDrainsParentCallbacksBeforeCheckingDependencyOwners() throws InterruptedException {
        var directory = new ResourceDirectory(failure -> fail(failure));
        var factory = directory.openFactory(producer);
        var child = factory.create();
        var childUse = child.retain();
        CountDownLatch parentStarted = new CountDownLatch(1);
        CountDownLatch releaseParent = new CountDownLatch(1);
        var parent = factory.create(() -> {
            parentStarted.countDown();
            try { releaseParent.await(); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            childUse.close();
        });
        child.close();
        parent.close();
        parentStarted.await();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread close = Thread.startVirtualThread(() -> {
            try { directory.close(); }
            catch (Throwable error) { failure.set(error); }
        });
        while (close.isAlive() && close.getState() != Thread.State.WAITING) Thread.yield();
        releaseParent.countDown();
        close.join();
        assertNull(failure.get());
    }

    @Test void finalReleaseRunsExactlyOnceOffTheCallingThread() {
        AtomicInteger destroyed = new AtomicInteger();
        AtomicReference<Thread> destructionThread = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        try (ResourceDirectory directory = new ResourceDirectory(failure -> fail(failure))) {
            var owner = directory.openFactory(producer).create(() -> {
                destructionThread.set(Thread.currentThread());
                destroyed.incrementAndGet();
            });
            owner.close();
            owner.close();
            directory.awaitRetirements();
            assertEquals(1, destroyed.get());
            assertNotSame(caller, destructionThread.get());
            assertThrows(IllegalStateException.class, owner::retain);
            assertThrows(IllegalStateException.class, () -> owner.retain());
        }
    }

    @Test void sceneAndFramesRetainAfterProducerReleases() {
        AtomicInteger destroyed = new AtomicInteger();
        try (ResourceDirectory directory = new ResourceDirectory(failure -> fail(failure))) {
            var owner = directory.openFactory(producer).create(destroyed::incrementAndGet);
            ResourceOwner reference = owner;
            var scene = reference.retain();
            owner.close();
            var firstFrame = scene.retain();
            var secondFrame = scene.retain();
            scene.close();
            firstFrame.close();
            directory.awaitRetirements();
            assertEquals(0, destroyed.get());
            secondFrame.close();
            directory.awaitRetirements();
            assertEquals(1, destroyed.get());
        }
    }

    @Test void dependenciesRemainAliveThroughTheirLastParent() {
        AtomicInteger destroyed = new AtomicInteger();
        try (ResourceDirectory directory = new ResourceDirectory(failure -> fail(failure))) {
            var factory = directory.openFactory(producer);
            var texture = factory.create(destroyed::incrementAndGet);
            var firstTextureUse = texture.retain();
            var secondTextureUse = texture.retain();
            var firstData = factory.create(firstTextureUse::close);
            var secondData = factory.create(secondTextureUse::close);
            texture.close();
            firstData.close();
            directory.awaitRetirements();
            assertEquals(0, destroyed.get());
            secondData.close();
            directory.awaitRetirements();
            assertEquals(1, destroyed.get());
        }
    }

    @Test void ownershipMayCrossContributionsButNotDevices() {
        try (ResourceDirectory directory = new ResourceDirectory(failure -> fail(failure));
             ResourceDirectory otherDevice = new ResourceDirectory(failure -> fail(failure))) {
            var owner = directory.openFactory(producer).create();
            directory.validate(owner);
            try (var shared = owner.retain()) {
                directory.invalidate(producer);
                try (var another = shared.retain()) {
                    assertNotSame(owner, another);
                }
                assertThrows(IllegalArgumentException.class,
                        () -> otherDevice.validate(shared));
            }
        }
    }

    @Test void captureDeduplicatesAndRollsBackOnReleasedDependency() {
        AtomicInteger destroyed = new AtomicInteger();
        try (ResourceDirectory directory = new ResourceDirectory(failure -> fail(failure))) {
            var factory = directory.openFactory(producer);
            var live = factory.create(destroyed::incrementAndGet);
            var released = factory.create();
            released.close();
            assertThrows(IllegalStateException.class,
                    () -> ResourceOwners.capture(List.of(live, released)));
            var scene = ResourceOwners.capture(List.of(live, live));
            var history = ResourceOwners.capture(List.of(live));
            live.close();
            scene.close();
            directory.awaitRetirements();
            assertEquals(0, destroyed.get());
            history.close();
            directory.awaitRetirements();
            assertEquals(1, destroyed.get());
        }
    }

    @Test void releasingClaimsConcurrentlyDisposesOnce() throws InterruptedException {
        AtomicInteger destroyed = new AtomicInteger();
        try (ResourceDirectory directory = new ResourceDirectory(failure -> fail(failure))) {
            var owner = directory.openFactory(producer).create(destroyed::incrementAndGet);
            var first = owner.retain();
            var second = owner.retain();
            CountDownLatch start = new CountDownLatch(1);
            Thread a = Thread.startVirtualThread(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                first.close();
            });
            Thread b = Thread.startVirtualThread(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                second.close();
            });
            owner.close();
            start.countDown();
            a.join();
            b.join();
            directory.awaitRetirements();
            assertEquals(1, destroyed.get());
        }
    }

    @Test void shutdownWaitsForOutstandingFrameAndCallback() {
        AtomicInteger destroyed = new AtomicInteger();
        try (ResourceDirectory directory = new ResourceDirectory(failure -> fail(failure))) {
            var factory = directory.openFactory(producer);
            var owner = factory.create(destroyed::incrementAndGet);
            var frame = owner.retain();
            directory.invalidate(producer);
            assertThrows(IllegalStateException.class, factory::create);
            directory.drain(frame::close);
            assertEquals(1, destroyed.get());
        }
    }

    @Test void callbackFailureDoesNotLoseOtherDestructionWork() {
        AtomicReference<Throwable> reported = new AtomicReference<>();
        RuntimeException expected = new RuntimeException("destruction failure");
        AtomicInteger destroyed = new AtomicInteger();
        try (ResourceDirectory directory = new ResourceDirectory(reported::set)) {
            var factory = directory.openFactory(producer);
            factory.create(() -> { throw expected; }).close();
            factory.create(destroyed::incrementAndGet).close();
            directory.awaitRetirements();
            assertSame(expected, reported.get());
            assertEquals(1, destroyed.get());
        }
    }
}
