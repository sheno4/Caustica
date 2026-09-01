package dev.comfyfluffy.caustica.engine.resource;

import dev.comfyfluffy.caustica.engine.session.ContributionOwner;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ResourceDirectoryTest {
    @Test
    void droppedUnusedGenerationRetiresOnlyThroughProgress() {
        AtomicInteger retired = new AtomicInteger();
        ResourceDirectory directory = new ResourceDirectory(failure -> { throw new AssertionError(failure); });
        ResourceContributionChannel channel = directory.openChannel(new ContributionOwner(1));
        var generation = channel.create(retired::incrementAndGet);

        generation.drop();
        generation.drop();
        assertEquals(0, retired.get());

        directory.progress();
        assertEquals(1, retired.get());
        assertDoesNotThrow(generation::drop);
        channel.drain();
        directory.close();
    }

    @Test
    void sealedGenerationWaitsForEveryEngineLease() {
        AtomicInteger retired = new AtomicInteger();
        ContributionOwner owner = new ContributionOwner(1);
        ResourceDirectory directory = new ResourceDirectory(failure -> { throw new AssertionError(failure); });
        ResourceContributionChannel channel = directory.openChannel(owner);
        var generation = channel.create(retired::incrementAndGet);

        assertThrows(IllegalStateException.class,
                () -> directory.acquire(owner, generation.reference()));
        generation.seal();
        generation.seal();
        directory.validate(owner, generation.reference());
        ResourceLease first = directory.acquire(owner, generation.reference());
        ResourceLease second = first.retain();
        generation.drop();
        assertTrue(directory.tryAcquire(generation.reference()).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> directory.acquire(owner, generation.reference()));

        first.close();
        assertThrows(IllegalStateException.class, first::retain);
        directory.progress();
        assertEquals(0, retired.get());
        second.close();
        second.close();
        assertEquals(0, retired.get());
        directory.progress();
        assertEquals(1, retired.get());
    }

    @Test
    void identityIsIndependentAndAcquisitionIsOwnerAndSessionScoped() {
        ContributionOwner firstOwner = new ContributionOwner(1);
        ContributionOwner secondOwner = new ContributionOwner(2);
        ResourceDirectory firstDirectory = new ResourceDirectory(failure -> { });
        ResourceDirectory secondDirectory = new ResourceDirectory(failure -> { });
        var first = firstDirectory.openChannel(firstOwner).create();
        var second = firstDirectory.openChannel(firstOwner).create();
        first.seal();
        second.seal();

        assertNotSame(first.reference(), second.reference());
        assertThrows(IllegalArgumentException.class,
                () -> firstDirectory.acquire(secondOwner, first.reference()));
        assertThrows(IllegalArgumentException.class,
                () -> secondDirectory.acquire(firstOwner, first.reference()));

        first.drop();
        second.drop();
        firstDirectory.progress();
    }

    @Test
    void canonicalNoResourceReferenceNeedsNoOwnerOrBorrow() {
        ContributionOwner owner = new ContributionOwner(1);
        ResourceDirectory directory = new ResourceDirectory(failure -> { });

        directory.validate(owner, ResourceRef.none());
        ResourceLease lease = directory.tryAcquire(ResourceRef.none()).orElseThrow();
        ResourceLease staticLease = ResourceLease.tryAcquire(ResourceRef.none()).orElseThrow();
        ResourceLease retained = lease.retain();
        lease.close();
        retained.close();
        staticLease.close();

        assertDoesNotThrow(directory::close);
    }

    @Test
    void trustedStaticAcquisitionRoutesThroughTheIssuingDirectory() {
        ContributionOwner owner = new ContributionOwner(1);
        ResourceDirectory directory = new ResourceDirectory(failure -> { });
        var generation = directory.openChannel(owner).create();
        assertThrows(IllegalStateException.class,
                () -> ResourceLease.tryAcquire(generation.reference()));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceLease.tryAcquire(new ResourceRef() { }));

        generation.seal();
        ResourceLease lease = ResourceLease.tryAcquire(generation.reference()).orElseThrow();
        generation.drop();
        assertTrue(ResourceLease.tryAcquire(generation.reference()).isEmpty());
        lease.close();
        directory.progress();
    }

    @Test
    void invalidationDropsAllOwnerGenerationsAndDrainRunsCallbacksInOrder() {
        List<Integer> retired = new ArrayList<>();
        ResourceDirectory directory = new ResourceDirectory(failure -> { throw new AssertionError(failure); });
        ResourceContributionChannel channel = directory.openChannel(new ContributionOwner(1));
        var created = channel.create(() -> retired.add(1));
        var sealed = channel.create(() -> retired.add(2));
        sealed.seal();

        channel.quiesce();
        assertThrows(IllegalStateException.class, () -> channel.create(() -> { }));
        created.seal();
        channel.invalidate();
        assertThrows(IllegalStateException.class, created::seal);
        assertEquals(List.of(), retired);

        channel.drain();
        assertEquals(List.of(1, 2), retired);
        directory.close();
    }

    @Test
    void callbackFailureDoesNotPreventRemainingRetirements() {
        RuntimeException expected = new RuntimeException("expected");
        List<Throwable> failures = new ArrayList<>();
        AtomicInteger retired = new AtomicInteger();
        ResourceDirectory directory = new ResourceDirectory(failures::add);
        ResourceContributionChannel channel = directory.openChannel(new ContributionOwner(1));
        var failing = channel.create(() -> { throw expected; });
        var succeeding = channel.create(retired::incrementAndGet);
        failing.drop();
        succeeding.drop();

        directory.progress();

        assertEquals(List.of(expected), failures);
        assertEquals(1, retired.get());
        channel.drain();
    }
}
